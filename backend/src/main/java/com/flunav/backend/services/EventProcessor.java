package com.flunav.backend.services;

import com.flunav.backend.context.MdcContext;
import com.flunav.backend.repositories.PathCacheRepository;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import flunav.context.UserContextHolder;
import flunav.events.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.context.SimulationBuildCacheContext;
import com.flunav.backend.utils.SimulationRunTiming;

/**
 * Central reducer for every domain event.
 *
 * The public asynchronous entry point preserves per-entity order, restores the
 * caller's live or simulation context, and acquires the appropriate topology
 * barrier. {@link #processEvent(DomainEvent, boolean)} then applies the event to
 * derived state. Only the outer path in live context appends events to ClickHouse;
 * historical builds use the non-broadcast reducer, while simulation playback may
 * broadcast without persisting simulation events as live history.
 *
 * Keep orchestration here and movement calculations in
 * {@link ItemMovementProcessor}. Routing choices belong in
 * {@link RoutingDecisionService}; destination interpretation belongs in the two
 * destination mapping services.
 */
@Service
public class EventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EventProcessor.class);

    private final ClickHouseService clickHouseService;
    private final TopologyProvider topologyProvider;
    private final ThroughputBucketService throughputBucketService;
    private final PathCacheRepository pathCacheRepository;
    private final AnomalyObservationService anomalyObservationService;
    private final SimulationInputService simulationInputService;
    private final MultiSimulationMetricsService multiSimulationMetricsService;


    private final ItemRoutingReducer itemRoutingReducer;
    private final TopologyReducer topologyReducer;
    private final AlarmReducer alarmReducer;
    private final ConfigurationReducer configurationReducer;
    private final EventReductionSupport eventReductionSupport;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, CompletableFuture<Void>> processingFutures = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock liveStateBarrier = new ReentrantReadWriteLock(true);
    private final ReentrantReadWriteLock[] simulationStateBarriers = createStateBarriers(256);

    public EventProcessor(
            ClickHouseService clickHouseService,
            TopologyProvider topologyProvider,
            ThroughputBucketService throughputBucketService,
            PathCacheRepository pathCacheRepository,
            AnomalyObservationService anomalyObservationService,
            SimulationInputService simulationInputService,
            MultiSimulationMetricsService multiSimulationMetricsService,
            ItemRoutingReducer itemRoutingReducer,
            TopologyReducer topologyReducer,
            AlarmReducer alarmReducer,
            ConfigurationReducer configurationReducer,
            EventReductionSupport eventReductionSupport) {
        this.clickHouseService = clickHouseService;
        this.topologyProvider = topologyProvider;
        this.throughputBucketService = throughputBucketService;
        this.pathCacheRepository = pathCacheRepository;
        this.anomalyObservationService = anomalyObservationService;
        this.simulationInputService = simulationInputService;
        this.multiSimulationMetricsService = multiSimulationMetricsService;
        this.itemRoutingReducer = itemRoutingReducer;
        this.topologyReducer = topologyReducer;
        this.alarmReducer = alarmReducer;
        this.configurationReducer = configurationReducer;
        this.eventReductionSupport = eventReductionSupport;
    }

    /**
     * Processes an event while preserving per-entity ordering.
     * Entity events are chained by id so unrelated items can run concurrently but a
     * single item's history is reduced in timestamp/order arrival sequence.
     */
    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast) {
        return process(event, shouldBroadcast, DatabaseContextHolder.getSimulationId() == null
                ? com.flunav.backend.models.simulation.EventOrigin.EXTERNAL_INGESTION
                : com.flunav.backend.models.simulation.EventOrigin.SIMULATION_GENERATED);
    }

    public CompletableFuture<Map<String, Object>> process(DomainEvent event, boolean shouldBroadcast,
            com.flunav.backend.models.simulation.EventOrigin origin) {
        final String entityId = (event instanceof EntityEvent e) ? e.getEntityId() : null;
        final String simulationId = DatabaseContextHolder.getSimulationId();
        final String senderId = UserContextHolder.getSenderId();

        if (entityId == null) {
            return executeOn(event, shouldBroadcast, executor, simulationId, senderId, origin);
        }

        String processingKey = (simulationId == null ? "live:" : "sim:" + simulationId + ":") + entityId;

        CompletableFuture<Map<String, Object>> taskResultFuture = new CompletableFuture<>();

        processingFutures.compute(processingKey, (id, previousTaskCompletion) -> {
            Supplier<CompletableFuture<Map<String, Object>>> workSupplier = () -> executeOn(event, shouldBroadcast,
                    executor, simulationId, senderId, origin);

            if (previousTaskCompletion == null || previousTaskCompletion.isDone()) {
                workSupplier.get().whenComplete((result, error) -> {
                    if (error != null)
                        taskResultFuture.completeExceptionally(error);
                    else
                        taskResultFuture.complete(result);
                });
            } else {
                previousTaskCompletion.whenComplete((ignored, throwable) -> {
                    workSupplier.get().whenComplete((result, error) -> {
                        if (error != null)
                            taskResultFuture.completeExceptionally(error);
                        else
                            taskResultFuture.complete(result);
                    });
                });
            }
            CompletableFuture<Void> chainedFuture = taskResultFuture.handle((result, error) -> null);
            chainedFuture.whenComplete((ignored, error) -> processingFutures.remove(processingKey, chainedFuture));
            return chainedFuture;
        });

        return taskResultFuture;
    }

    /**
     * Chooses synchronous or async execution based on simulation context.
     * Simulation replay stays on the current thread so ThreadLocal simulation and
     * virtual-time state cannot be lost across an executor boundary.
     */
    private CompletableFuture<Map<String, Object>> executeOn(DomainEvent event, boolean shouldBroadcast,
            Executor executor, String simulationId, String senderId,
            com.flunav.backend.models.simulation.EventOrigin origin) {
        if (simulationId != null) {
            try {
                return CompletableFuture
                        .completedFuture(executeBusinessLogic(event, shouldBroadcast, simulationId, senderId, origin));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        return CompletableFuture.supplyAsync(
                () -> executeBusinessLogic(event, shouldBroadcast, simulationId, senderId, origin), executor);
    }

    /**
     * Enters the context needed to reduce one event into derived state.
     * Live events are persisted to ClickHouse around processing according to their
     * replay needs, while simulation events update only isolated derived stores.
     */
    private Map<String, Object> executeBusinessLogic(DomainEvent event, boolean shouldBroadcast, String simulationId,
            String senderId, com.flunav.backend.models.simulation.EventOrigin origin) {
        String entityId = event instanceof EntityEvent entityEvent ? entityEvent.getEntityId() : null;
        String mode = simulationId == null ? "LIVE" : "SIMULATION";
        String effectiveSenderId = event.getSenderId() != null ? event.getSenderId() : senderId;
        long startedAt = System.nanoTime();

        ReentrantReadWriteLock stateBarrier = simulationId == null
                ? liveStateBarrier
                : simulationStateBarriers[Math.floorMod(simulationId.hashCode(), simulationStateBarriers.length)];
        Lock stateLock = isDestructiveTopologyEvent(event) ? stateBarrier.writeLock() : stateBarrier.readLock();
        stateLock.lock();
        try (var simulationContext = DatabaseContextHolder.enterSimulationContext(simulationId);
                var senderContext = UserContextHolder.enterSenderContext(effectiveSenderId);
                var eventContext = MdcContext.withValues(Map.of(
                        "event_type", event.getEventType(),
                        "event_id", event.getEventId(),
                        "entity_id", entityId == null ? "" : entityId,
                        "mode", mode))) {
            logProcessingStarted(shouldBroadcast);

            try {
                Map<String, Object> resultMap = processEvent(event, shouldBroadcast);
                if (event instanceof AlarmRaisedEvent || event instanceof AlarmClearedEvent
                        || event instanceof ComponentAlarmRaisedEvent || event instanceof ComponentAlarmClearedEvent) {
                    try {
                        clickHouseService.saveAlarmFact(event, simulationId, eventReductionSupport.alarmAffectedItems(event));
                    } catch (RuntimeException analyticsFailure) {
                        logger.error("Alarm state was reduced but its analytics fact could not be written", analyticsFailure);
                    }
                }
                if (simulationId == null && !(event instanceof PathTraversedEvent)
                        && !(event instanceof AnomalyEvaluationTickEvent)) {
                    clickHouseService.saveEventAsync(event);
                    simulationInputService.onLiveEvent(event, origin);
                }
                logProcessingCompleted(shouldBroadcast, elapsedMillis(startedAt));
                return resultMap;
            } catch (Exception e) {
                logProcessingFailed(shouldBroadcast, elapsedMillis(startedAt), e);
                throw new CompletionException(e);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private static ReentrantReadWriteLock[] createStateBarriers(int size) {
        ReentrantReadWriteLock[] barriers = new ReentrantReadWriteLock[size];
        for (int index = 0; index < barriers.length; index++) {
            barriers[index] = new ReentrantReadWriteLock(true);
        }
        return barriers;
    }

    /**
     * Topology deletion takes an exclusive namespace lock so no item reducer can
     * enter a location or conveyor between the hot-state sweep and graph removal.
     */
    private boolean isDestructiveTopologyEvent(DomainEvent event) {
        return event instanceof LocationDeletedEvent
                || event instanceof ConnectionDeletedEvent
                || event instanceof ConnectionActivatedEvent
                || event instanceof ConnectionDeactivatedEvent
                || event instanceof ReleaseStagingConveyorEvent
                || event instanceof ConnectionTypeChangedEvent;
    }

    /**
     * Emits structured start logging for one event reduction.
     * The MDC already contains event identity and mode, so this method only adds
     * reduction-specific details.
     */
    private void logProcessingStarted(boolean shouldBroadcast) {
        logger.atDebug()
                .addKeyValue("broadcast", shouldBroadcast)
                .log("Event processing started");
    }

    /**
     * Emits structured completion logging after derived state was updated.
     * Duration is measured outside storage calls so slow reducers are visible in
     * logs without changing event semantics.
     */
    private void logProcessingCompleted(boolean shouldBroadcast, long durationMillis) {
        logger.atDebug()
                .addKeyValue("broadcast", shouldBroadcast)
                .addKeyValue("duration_ms", durationMillis)
                .log("Event processing completed");
    }

    /**
     * Emits structured failure logging before the processing future is failed.
     * The original exception is preserved so retry and caller handling can still
     * inspect the actual failure type.
     */
    private void logProcessingFailed(boolean shouldBroadcast, long durationMillis, Exception error) {
        logger.atError()
                .addKeyValue("broadcast", shouldBroadcast)
                .addKeyValue("duration_ms", durationMillis)
                .setCause(error)
                .log("Event processing failed");
    }

    /**
     * Converts the monotonic processing timer into milliseconds for logs.
     * System.nanoTime is used so wall-clock or virtual-time changes do not affect
     * duration measurement.
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * Retries OrientDB write conflicts around one reducer operation.
     * The event reduction remains single-call from the caller's perspective while
     * transient concurrent modification failures get a short exponential backoff.
     */
    private <T> T executeWithRetry(Supplier<T> operation) {
        final int MAX_RETRIES = 5;
        int attempt = 0;
        while (true) {
            try {
                return operation.get();
            } catch (OConcurrentModificationException | java.util.NoSuchElementException e) {
                attempt++;
                if (attempt >= MAX_RETRIES)
                    throw e;
                try {
                    Thread.sleep(50 * (long) Math.pow(2, attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
    }

    /**
     * Reduces an event and broadcasts the resulting state changes.
     * This overload is used for normal live processing where clients and metrics
     * should observe the event effects.
     */
    public Map<String, Object> processEvent(DomainEvent event) {
        return processEvent(event, true);
    }

    /**
     * Reduces an event without broadcasting or throughput accounting.
     * Replay and restore paths use this when rebuilding derived state should not
     * look like new live activity.
     */
    public Map<String, Object> processEventWithoutBroadcast(DomainEvent event) {
        return processEvent(event, false);
    }

    /**
     * Applies the domain effects for one event.
     * This is the central reducer that updates OrientDB, Redis, WebSocket clients,
     * routing assignments, and analytics side effects according to event type.
     */
    public Map<String, Object> processEvent(DomainEvent event, boolean shouldBroadcast) {
        long eventStarted = SimulationRunTiming.tick();
        UserContextHolder.setSenderId(event.getSenderId());
        boolean topologyMutation = isTopologyMutation(event);
        if (topologyMutation && topologyProvider instanceof CachingTopologyProvider cached) {
            cached.invalidate();
        }
        try (var bypass = topologyMutation ? SimulationBuildCacheContext.bypassTopology()
                : (SimulationBuildCacheContext.TopologyBypass) () -> {}) {
            return this.<Map<String, Object>>executeWithRetry(() -> {
            Map<String, Object> result = switch (event) {
                case ItemCreatedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemPositionChangedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemMovementCheckEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemProcessingCompletedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemPositionDeletedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemRenamedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemSpeedChangedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemDeactivatedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemActivatedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemPropertiesUpdatedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemPriorityUpdatedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemDeletedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemDestinationEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemRoutingDecisionRequestedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemPathChangedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ItemExitedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ChuteEmptyEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case ReleaseStagingConveyorEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case PathTraversedEvent e -> itemRoutingReducer.reduce(e, shouldBroadcast, this::processEvent);
                case LocationCreatedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationPropertiesUpdatedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationCoordinatesChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationCapacityChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationProcessingTimeChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationTypeChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationDeletedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionCreatedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionSpeedChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionLengthChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionConstraintsChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionPropertiesUpdatedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionActivatedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionDeactivatedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionTypeChangedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case LocationAddToMainPath e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionRemoveFromMainPath e -> topologyReducer.reduce(e, shouldBroadcast);
                case ConnectionDeletedEvent e -> topologyReducer.reduce(e, shouldBroadcast);
                case AlarmRaisedEvent e -> alarmReducer.reduce(e, shouldBroadcast);
                case AlarmClearedEvent e -> alarmReducer.reduce(e, shouldBroadcast);
                case ComponentAlarmRaisedEvent e -> alarmReducer.reduce(e, shouldBroadcast);
                case ComponentAlarmClearedEvent e -> alarmReducer.reduce(e, shouldBroadcast);
                case AnomalyEvaluationTickEvent e -> alarmReducer.reduce(e, shouldBroadcast);
                case MapDestinationsEvent e -> configurationReducer.reduce(e, shouldBroadcast);
                case MapDestinationExitsEvent e -> configurationReducer.reduce(e, shouldBroadcast);
                case MapSensorMappingsEvent e -> configurationReducer.reduce(e, shouldBroadcast);
                case MapDisplayRulesEvent e -> configurationReducer.reduce(e, shouldBroadcast);
                default -> {
                    logger.debug("Event type {} handled by fallback logic or ignored",
                            event.getClass().getSimpleName());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }
            };
            invalidatePathCacheAfterTopologyChange(event, result);
            anomalyObservationService.collectTopologyChange(event);
            multiSimulationMetricsService.recordSuccessfulReduction(event, result);
            if (shouldBroadcast) {
                throughputBucketService.recordSuccessfulReduction(event, result);
            }
            return result;
            });
        } finally {
            SimulationRunTiming.record("event." + event.getEventType(), eventStarted);
            if (topologyMutation && topologyProvider instanceof CachingTopologyProvider cached) {
                cached.invalidate();
            }
        }
    }

    private boolean isTopologyMutation(DomainEvent event) {
        String type = event.getEventType();
        return type.startsWith("LOCATION_") || type.startsWith("CONNECTION_")
                || type.startsWith("ALARM_") || type.startsWith("COMPONENT_ALARM_");
    }

    /**
     * Clears cached route paths only after topology reducers succeed.
     * The active DatabaseContextHolder namespace decides whether live or one
     * simulation cache is invalidated, keeping isolated graph states independent.
     */
    private void invalidatePathCacheAfterTopologyChange(DomainEvent event, Map<String, Object> result) {
        if ((event instanceof AlarmRaisedEvent
                || event instanceof AlarmClearedEvent
                || event instanceof ComponentAlarmRaisedEvent
                || event instanceof ComponentAlarmClearedEvent
                || event instanceof ConnectionActivatedEvent
                || event instanceof ConnectionDeactivatedEvent)
                && Boolean.TRUE.equals(result.get("effectiveActivityChanged"))) {
            pathCacheRepository.invalidateCurrentNamespace();
            return;
        }
        if (event instanceof ConnectionCreatedEvent
                || event instanceof ConnectionDeletedEvent
                || event instanceof ConnectionSpeedChangedEvent
                || event instanceof ConnectionLengthChangedEvent
                || event instanceof ConnectionTypeChangedEvent
                || event instanceof LocationAddToMainPath
                || event instanceof ConnectionRemoveFromMainPath
                || event instanceof LocationCreatedEvent
                || event instanceof LocationDeletedEvent
                || event instanceof LocationTypeChangedEvent
                || event instanceof LocationProcessingTimeChangedEvent) {
            pathCacheRepository.invalidateCurrentNamespace();
        }
    }

    /**
     * Runs a live snapshot operation after all current live reductions finish and
     * before any new live reduction starts. Simulation reducers use isolated stores
     * and therefore do not need to block snapshot capture.
     */
    public <T> T withLiveSnapshotBarrier(Supplier<T> snapshotWork) {
        Lock writeLock = liveStateBarrier.writeLock();
        writeLock.lock();
        try {
            return snapshotWork.get();
        } finally {
            writeLock.unlock();
        }
    }

}
