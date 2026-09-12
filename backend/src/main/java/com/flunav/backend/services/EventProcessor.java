package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Location;
import com.flunav.backend.context.MdcContext;
import com.flunav.backend.context.AnomalyProcessingContext;
import com.flunav.backend.models.analytics.AnomalyProcessingMode;
import com.flunav.backend.exception.DuplicateItemException;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.analytics.LocationTransitMetric;
import com.flunav.backend.models.input.ItemInput;
import com.flunav.backend.models.input.LocationInput;
import com.flunav.backend.models.response.ConveyorResponse;
import com.flunav.backend.models.response.DisplayRuleColorResult;
import com.flunav.backend.models.response.DisplayRuleVisualStyle;
import com.flunav.backend.models.response.ItemResponse;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveLocationRepository; // 1. IMPORT
import com.flunav.backend.repositories.PathCacheRepository;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import flunav.context.UserContextHolder;
import flunav.events.*;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import flunav.types.ActiveAlarm;
import jakarta.annotation.PreDestroy;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
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

@Service
public class EventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(EventProcessor.class);

    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final ConveyorService conveyorService;
    private final WebSocketService webSocketService;
    private final PathfindingService pathfindingService;
    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveLocationRepository liveLocationRepository; // 2. INJECT
    private final SimulationService simulationService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final DisplayRulesService displayRulesService;
    private final GraphService graphService;
    private final AmqpTemplate amqpTemplate;
    private final String itemEventsRoutingKey;
    private final String commandsQueue;
    private final TimeService timeService;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final LocationService locationService;
    private final ItemMovementProcessor itemMovementProcessor;
    private final DestinationMappingService destinationMappingService;
    private final DestinationExitMappingService destinationExitMappingService;
    private final RoutingDecisionService routingDecisionService;
    private final RoutingCoordinator routingCoordinator;
    private final PathAssignmentPublisher pathAssignmentPublisher;
    private final ThroughputBucketService throughputBucketService;
    private final PathCacheRepository pathCacheRepository;
    private final OperationalAnalyticsService operationalAnalyticsService;
    private final AnomalyObservationService anomalyObservationService;
    private final AnomalyEngine anomalyEngine;
    private final SimulationInputService simulationInputService;

    ModelMapper modelMapper = new ModelMapper();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, CompletableFuture<Void>> processingFutures = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock liveStateBarrier = new ReentrantReadWriteLock(true);
    private final ReentrantReadWriteLock[] simulationStateBarriers = createStateBarriers(256);

    public EventProcessor(
            ClickHouseService clickHouseService,
            ItemService itemService,
            ConveyorService conveyorService,
            WebSocketService webSocketService,
            PathfindingService pathfindingService,
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            LiveLocationRepository liveLocationRepository, // 3. INJECT
            @Lazy SimulationService simulationService,
            LiveSystemScheduler liveSystemScheduler,
            DisplayRulesService displayRulesService,
            @Lazy GraphService graphService,
            LocationService locationService,
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.routing-key.item-events}") String itemEventsRoutingKey,
            @Value("${rabbitmq.queue.commands}") String commandsQueue,
            TimeService timeService,
            TopologyProvider topologyProvider,
            ItemMovementProcessor itemMovementProcessor,
            DestinationMappingService destinationMappingService,
            DestinationExitMappingService destinationExitMappingService,
            RoutingDecisionService routingDecisionService,
            RoutingCoordinator routingCoordinator,
            PathAssignmentPublisher pathAssignmentPublisher,
            ThroughputBucketService throughputBucketService,
            PathCacheRepository pathCacheRepository,
            OperationalAnalyticsService operationalAnalyticsService,
            AnomalyObservationService anomalyObservationService,
            @Lazy AnomalyEngine anomalyEngine,
            SimulationInputService simulationInputService,
            @Value("${simulation.manage-logic:true}") boolean manageLogic) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.conveyorService = conveyorService;
        this.webSocketService = webSocketService;
        this.pathfindingService = pathfindingService;
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveLocationRepository = liveLocationRepository; // 4. INJECT
        this.simulationService = simulationService;
        this.locationService = locationService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.displayRulesService = displayRulesService;
        this.graphService = graphService;
        this.amqpTemplate = amqpTemplate;
        this.itemEventsRoutingKey = itemEventsRoutingKey;
        this.commandsQueue = commandsQueue;
        this.timeService = timeService;
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.destinationMappingService = destinationMappingService;
        this.destinationExitMappingService = destinationExitMappingService;
        this.routingDecisionService = routingDecisionService;
        this.routingCoordinator = routingCoordinator;
        this.pathAssignmentPublisher = pathAssignmentPublisher;
        this.throughputBucketService = throughputBucketService;
        this.pathCacheRepository = pathCacheRepository;
        this.operationalAnalyticsService = operationalAnalyticsService;
        this.anomalyObservationService = anomalyObservationService;
        this.anomalyEngine = anomalyEngine;
        this.simulationInputService = simulationInputService;
        this.manageLogic = manageLogic;
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
                        clickHouseService.saveAlarmFact(event, simulationId, alarmAffectedItems(event));
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
        UserContextHolder.setSenderId(event.getSenderId());
        return this.<Map<String, Object>>executeWithRetry(() -> {
            Map<String, Object> result = switch (event) {
                case ItemCreatedEvent e -> {
                    try {
                        validatePriority(e.getPriority());
                        validateItemProperties(e.getProperties());
                        var item = new ItemInput(e);
                        AppliedDestination appliedDestination = routingCoordinator.withRoutingLock(() -> {
                            AppliedDestination destination = applyDestinationToCreatedItem(item, e.getTimestamp());
                            itemService.createItem(item);
                            return destination;
                        });
                        publishDestinationCommandIfNeeded(e, item, appliedDestination, shouldBroadcast);
                        pathAssignmentPublisher.publishIfAssigned(
                                item.getId(),
                                appliedDestination.selectedExitId(),
                                appliedDestination.routingStatus(),
                                appliedDestination.path(),
                                e.getTimestamp(),
                                shouldBroadcast);

                        if (item.getLocationId() != null) {
                            var positionType = topologyProvider.getPositionType(item.getLocationId());
                            if (positionType == PositionType.CONVEYOR) {
                                liveConveyorRepository.addItemToConveyor(item.getLocationId(), e.getEntityId(),
                                        e.getTimestamp());
                                itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), item.getLocationId(),
                                        e.getTimestamp(),
                                        e.getProgress(), null, shouldBroadcast);
                            } else {
                                itemMovementProcessor.processLocationEntry(e.getEntityId(), item.getLocationId(),
                                        e.getTimestamp(), shouldBroadcast);
                            }
                        }

                        if (shouldBroadcast) {
                            ItemResponse response = modelMapper.map(item, ItemResponse.class);
                            DestinationMappingService.RushPriority rush = destinationMappingService.evaluateRush(
                                    itemRootFields(item), item.getProperties(), item.getDestinations(),
                                    item.getPriority(), e.getTimestamp());
                            response.setEffectivePriority(rush.effectivePriority());
                            response.setRushActive(rush.rushActive());
                            DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                                    itemRootFields(item), item.getProperties(), this.displayRulesService.getDisplayRules());
                            if (style != null) {
                                response.setCustomColor(style.getFillColor());
                                response.setCustomBorderColor(style.getBorderColor());
                                response.setCustomBorderWidth(style.getBorderWidth());
                            }
                            webSocketService.broadcastItemCreated(response, e.getTimestamp());
                        }
                        yield Map.of("status", "CREATED", "itemId", e.getEntityId());
                    } catch (DuplicateItemException die) {
                        logger.warn("Received a duplicate ItemCreatedEvent for existing item '{}'. Ignoring event.",
                                e.getEntityId());
                        yield Map.of("status", "IGNORED_DUPLICATE", "itemId", e.getEntityId());
                    }
                }

                case MapDestinationsEvent e -> {
                    destinationMappingService.saveMapDestinations(e);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case MapDestinationExitsEvent e -> {
                    destinationExitMappingService.saveMappings(e);
                    retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case MapDisplayRulesEvent e -> {
                    displayRulesService.updateDisplayRules(e.getRules());
                    DisplayRuleColorResult styles = graphService.computeColors(e.getRules());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY", "colors", styles);
                }

                case ItemPositionChangedEvent e -> {
                    var positionType = topologyProvider.getPositionType(e.getLocationId());

                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    String previousPosId = (lastState != null) ? lastState.getPositionId() : null;
                    var lastPositionType = (lastState != null) ? lastState.getType() : null;

                    // If we are moving to a location (conveyor or node) that is NOT connected to
                    // the previous one,
                    // we consider it a teleport.
                    boolean isTeleport = false;
                    if (previousPosId != null && lastPositionType != null) {
                        isTeleport = !pathfindingService.arePositionsConnected(previousPosId, lastPositionType,
                                e.getLocationId(), positionType);
                    }

                    // --- REMOVE FROM PREVIOUS POSITION ---
                    if (previousPosId != null) {
                        if (PositionType.CONVEYOR.equals(lastPositionType)) {
                            liveConveyorRepository.removeItemFromConveyor(previousPosId, e.getEntityId());
                        } else {
                            var previousLocation = topologyProvider.getLocationById(previousPosId);
                            // Only remove locations that own transient occupancy state.
                            if (previousLocation != null
                                    && (previousLocation.getType() == LocationType.CHUTE
                                            || previousLocation.getType() == LocationType.TIMED_NODE)) {
                                liveLocationRepository.removeItemFromLocation(previousPosId, e.getEntityId());
                            }
                        }
                    }

                    if (isTeleport && previousPosId != null && lastPositionType != null) {
                        // TODO: record into ch?
                    }

                    itemService.updateItemPosition(e.getEntityId(), e.getLocationId(), positionType, e.getTimestamp(),
                            e.getProgress(), null);

                    // --- ADD TO NEW POSITION ---
                    if (positionType == PositionType.CONVEYOR) {
                        liveConveyorRepository.addItemToConveyor(e.getLocationId(), e.getEntityId(), e.getTimestamp());
                        itemMovementProcessor.handleItemEntryToConveyor(e.getEntityId(), e.getLocationId(),
                                e.getTimestamp(), e.getProgress(),
                                previousPosId, shouldBroadcast);
                    } else {
                        itemMovementProcessor.processLocationEntry(e.getEntityId(), e.getLocationId(),
                                e.getTimestamp(), shouldBroadcast);
                    }

                    anomalyObservationService.collectPositionChange(e, lastState, positionType);

                    if (shouldBroadcast) {
                        webSocketService.broadcastPositionUpdate(e.getEntityId(), e.getLocationId(), e.getTimestamp(),
                                positionType, e.getProgress());
                    }

                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemProcessingCompletedEvent e -> {
                    itemMovementProcessor.processTimedNodeCompletion(e.getEntityId(), e.getLocationId(),
                            e.getTimestamp(), shouldBroadcast);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPositionDeletedEvent e -> {
                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    // This logic is now handled by liveItemRepository.deleteItem(), which is more
                    // robust
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    itemService.deleteItem(e.getEntityId());
                    if (shouldBroadcast)
                        webSocketService.broadcastPositionLost(e.getEntityId(), e.getTimestamp());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemRenamedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("name", e.getNewName())));
                    item.setName(e.getNewName());
                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("name", e.getNewName());
                        addRushFields(updates, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemSpeedChangedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("speed", e.getSpeed())));

                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeactivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", false)));
                    item.stop();
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("active", false);
                        addRushFields(updates, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemActivatedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("active", true)));
                    item.resume();
                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("active", true);
                        addRushFields(updates, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPropertiesUpdatedEvent e -> {
                    validateItemProperties(e.getProperties());
                    var item = itemService.getItemById(e.getEntityId());
                    item.updateProperties(e);
                    Map<String, Object> map = new HashMap<>();
                    map.put("properties", item.getProperties());
                    var updateModel = new UpdateModel(item.getId(), map);
                    itemService.updateItem(updateModel);
                    if (shouldBroadcast) {
                        DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                                itemRootFields(item), item.getProperties(), this.displayRulesService.getDisplayRules());
                        map.put("customColor", style != null ? style.getFillColor() : null);
                        map.put("customBorderColor", style != null ? style.getBorderColor() : null);
                        map.put("customBorderWidth", style != null ? style.getBorderWidth() : null);
                        addRushFields(map, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemPriorityUpdatedEvent e -> {
                    validatePriority(e.getPriority());
                    var item = itemService.getItemById(e.getEntityId());
                    if (item == null) {
                        throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                    }
                    itemService.updateItem(new UpdateModel(item.getId(), Map.of("priority", e.getPriority())));
                    item.setPriority(e.getPriority());
                    if (shouldBroadcast) {
                        DisplayRuleVisualStyle style = displayRulesService.applyDisplayRules(
                                itemRootFields(item), item.getProperties(), displayRulesService.getDisplayRules());
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("priority", e.getPriority());
                        updates.put("customColor", style != null ? style.getFillColor() : null);
                        updates.put("customBorderColor", style != null ? style.getBorderColor() : null);
                        updates.put("customBorderWidth", style != null ? style.getBorderWidth() : null);
                        addRushFields(updates, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDeletedEvent e -> {
                    var lastState = liveItemRepository.getItemState(e.getEntityId());
                    if (lastState != null) {
                        if (lastState.getPositionId() != null && lastState.getType() != null) {
                            recordPathTraversal(new PathTraversedEvent(
                                    e.getEntityId(),
                                    lastState.getPositionId(),
                                    lastState.getType(),
                                    lastState.getPositionId(),
                                    lastState.getType(),
                                    List.of(lastState.getPositionId()),
                                    e.getTimestamp()));
                        }
                    }
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    itemService.deleteItem(e.getEntityId());
                    liveItemRepository.deleteItem(e.getEntityId());

                    if (shouldBroadcast)
                        webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemDestinationEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    if (item == null) {
                        throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                    }
                    List<String> previousPath = item.getPath();
                    RoutingDecisionService.RoutingDecision decision = item.getPositionId() == null
                            ? RoutingDecisionService.RoutingDecision.none()
                            : routingCoordinator.withRoutingLock(() -> {
                                RoutingDecisionService.RoutingDecision selected =
                                        routingDecisionService.selectRouteToExit(
                                                item,
                                                item.getPositionId(),
                                                item.getPositionType() != null ? item.getPositionType()
                                                        : PositionType.LOCATION,
                                                e.getLocationId(),
                                                e.getTimestamp());
                                item.setSelectedExitId(selected.selectedExitId());
                                item.setRoutingStatus(selected.routingStatus());
                                item.setRoutingStatusUpdatedAt(e.getTimestamp());
                                item.setPath(selected.path());
                                itemService.updateItemRouting(
                                        item.getId(), item.getDestinations(), selected.selectedExitId(),
                                        selected.routingStatus(), e.getTimestamp(), selected.path());
                                return selected;
                            });
                    pathAssignmentPublisher.publishIfAssigned(
                            item.getId(),
                            decision.selectedExitId(),
                            decision.routingStatus(),
                            decision.path(),
                            e.getTimestamp(),
                            shouldBroadcast);

                    recordCurrentPathTraversal(item.getId(), decision.path(), e.getTimestamp());
                    operationalAnalyticsService.recordRecirculation(
                            item.getId(), previousPath, decision.path(), e.getTimestamp());

                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("selectedExitId", decision.selectedExitId());
                        updates.put("routingStatus", decision.routingStatus());
                        updates.put("routingStatusUpdatedAt", e.getTimestamp());
                        updates.put("path", decision.path());
                        addRushFields(updates, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemRoutingDecisionRequestedEvent e -> {
                    var item = itemService.getItemById(e.getEntityId());
                    if (item == null) {
                        throw new IllegalArgumentException("Item does not exist: " + e.getEntityId());
                    }
                    if (!manageLogic) {
                        yield Map.of("status", "IGNORED_MANAGED_LOGIC_DISABLED");
                    }

                    List<String> previousPath = item.getPath();
                    RoutingDecisionService.RoutingDecision decision = routingCoordinator.withRoutingLock(() -> {
                        RoutingDecisionService.RoutingDecision selected = routingDecisionService.selectRoute(
                                item, e.getDecisionPointId(), PositionType.LOCATION, e.getTimestamp());
                        itemService.updateItemRouting(
                                item.getId(), item.getDestinations(), selected.selectedExitId(),
                                selected.routingStatus(), e.getTimestamp(), selected.path());
                        return selected;
                    });
                    pathAssignmentPublisher.publishIfAssigned(
                            item.getId(),
                            decision.selectedExitId(),
                            decision.routingStatus(),
                            decision.path(),
                            e.getTimestamp(),
                            shouldBroadcast);
                    operationalAnalyticsService.recordRecirculation(
                            item.getId(), previousPath, decision.path(), e.getTimestamp());

                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("selectedExitId", decision.selectedExitId());
                        updates.put("routingStatus", decision.routingStatus());
                        updates.put("routingStatusUpdatedAt", e.getTimestamp());
                        updates.put("path", decision.path());
                        addRushFields(updates, item, e.getTimestamp());
                        webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), e.getTimestamp());
                    }

                    yield Map.of(
                            "status", "PROCESSED_SUCCESSFULLY",
                            "nextConveyorId", Objects.toString(decision.nextConveyorId(), ""),
                            "selectedExitId", Objects.toString(decision.selectedExitId(), ""));
                }

                case ItemPathChangedEvent e -> {
                    RedisLiveItem previousState = liveItemRepository.getItemState(e.getEntityId());
                    itemService.updateItemPath(e.getEntityId(), e.getPath());
                    recordCurrentPathTraversal(e.getEntityId(), e.getPath(), e.getTimestamp());
                    operationalAnalyticsService.recordRecirculation(
                            e.getEntityId(), previousState != null ? previousState.getPath() : null,
                            e.getPath(), e.getTimestamp());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(
                                new UpdateModel(e.getEntityId(), Map.of("path", e.getPath())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                // --- LOCATION EVENTS (Nodes) ---

                case LocationCreatedEvent e -> {
                    var location = new LocationInput(e);
                    locationService.createLocation(location);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationCreated(location, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationPropertiesUpdatedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateProperties(e);
                    Map<String, Object> map = new HashMap<>();
                    map.put("properties", location.getProperties());
                    var updateModel = new UpdateModel(location.getId(), map);
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                                locationRootFields(location),
                                location.getProperties(), this.displayRulesService.getDisplayRules());
                        map.put("customColor", style != null ? style.getFillColor() : null);
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationCoordinatesChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.updateCoordinates(e.getLatitude(), e.getLongitude());
                    var updateModel = new UpdateModel(location.getId(),
                            Map.of("latitude", location.getLatitude(), "longitude", location.getLongitude()));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationCapacityChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    Integer oldCapacity = location.getCapacity();
                    location.updateCapacity(e.getCapacity());
                    var updateModel = new UpdateModel(location.getId(), Map.of("capacity", location.getCapacity()));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    if (location.getType() == LocationType.CHUTE
                            && e.getCapacity() != null
                            && (oldCapacity == null || e.getCapacity() > oldCapacity)) {
                        retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ItemExitedEvent e -> {
                    RedisLiveItem lastState = liveItemRepository.getItemState(e.getEntityId());
                    if (lastState == null) {
                        yield Map.of("status", "IGNORED_MISSING", "itemsExited", 0L);
                    }
                    String positionId = lastState.getPositionId() != null
                            ? lastState.getPositionId()
                            : e.getLocationId();
                    PositionType positionType = lastState.getType() != null
                            ? lastState.getType()
                            : PositionType.LOCATION;
                    operationalAnalyticsService.recordSuccessfulExit(e, positionId, lastState, e.getEntityId());
                    if (positionId != null) {
                        recordPathTraversal(new PathTraversedEvent(
                                e.getEntityId(),
                                positionId,
                                positionType,
                                positionId,
                                positionType,
                                List.of(positionId),
                                e.getTimestamp()));
                    }
                    itemMovementProcessor.cancelScheduledEvent(e.getEntityId());
                    liveItemRepository.deleteItem(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemDeleted(e.getEntityId(), e.getTimestamp());
                    }
                    retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                    if (manageLogic && positionId != null) {
                        itemMovementProcessor.wakeUpPrecedingConveyors(positionId);
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY", "itemsExited", 1L);
                }

                case LocationProcessingTimeChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    Long timeToProcessMs = e.getTimeToProcessMs() != null && e.getTimeToProcessMs() > 0L
                            ? e.getTimeToProcessMs()
                            : 0L;
                    location.updateTimeToProcessMs(timeToProcessMs);
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("timeToProcessMs", timeToProcessMs);
                    var updateModel = new UpdateModel(location.getId(), updates);
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationTypeChangedEvent e -> {
                    var location = locationService.getLocationById(e.getEntityId());
                    location.setType(e.getLocationType());
                    var updateModel = new UpdateModel(location.getId(), Map.of("type", location.getType().getValue()));
                    locationService.updateLocation(updateModel);
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationPropertiesUpdated(updateModel, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationDeletedEvent e -> {
                    Set<String> removedPositionIds = new HashSet<>();
                    removedPositionIds.add(e.getEntityId());
                    topologyProvider.getAllConveyors().stream()
                            .filter(conveyor -> e.getEntityId().equals(conveyor.getSourceLocationId())
                                    || e.getEntityId().equals(conveyor.getTargetLocationId()))
                            .map(Conveyor::getId)
                            .forEach(removedPositionIds::add);
                    removeHotItemsAtPositions(removedPositionIds, e.getTimestamp(), shouldBroadcast);
                    removedPositionIds.stream()
                            .filter(positionId -> !e.getEntityId().equals(positionId))
                            .forEach(liveConveyorRepository::deleteConveyor);
                    liveLocationRepository.deleteLocation(e.getEntityId());
                    locationService.deleteLocation(e.getEntityId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastLocationDeleted(e.getEntityId(), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                // --- CONNECTION EVENTS (Conveyors/Edges) ---

                case ConnectionCreatedEvent e -> {
                    conveyorService.createConveyor(
                            e.getConnectionId(),
                            e.getSourceId(),
                            e.getTargetId(),
                            e.getName(),
                            e.getLength(),
                            e.getSpeed(),
                            e.getMinDistance(),
                            e.getMainPath(),
                            e.getIsActive(),
                            e.getType(),
                            e.getCapacity(),
                            e.getProperties());
                    operationalAnalyticsService.recordSimulationConnectionSignal(
                            e, e.getConnectionId(), e.getIsActive(), e.getSpeed(), e.getSourceId(), e.getTargetId());
                    if (shouldBroadcast) {
                        DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                                connectionRootFields(e),
                                e.getProperties(), this.displayRulesService.getDisplayRules());
                        String customColor = style != null ? style.getFillColor() : null;
                        webSocketService.broadcastConnectionCreated(new ConveyorResponse(e.getConnectionId(),
                                e.getSourceId(), e.getTargetId(), e.getName(), e.getLength(), e.getSpeed(),
                                e.getMinDistance(),
                                e.getType(),
                                e.getIsActive(), e.getMainPath(), e.getCapacity(),
                                e.getProperties(), customColor, e.getIsActive(), List.of()), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionSpeedChangedEvent e -> {
                    var conveyor = topologyProvider.getConveyorById(e.getEntityId());
                    if (conveyor != null) {
                        checkpointItems(e.getEntityId(), conveyor.getSpeed(), e.getTimestamp());
                        double oldSpeed = conveyor.getSpeed();
                        conveyor.setSpeed(e.getSpeed());
                        conveyorService.updateConveyor(conveyor);
                        operationalAnalyticsService.recordSimulationConnectionSignal(
                                e, conveyor.getId(), null, e.getSpeed(),
                                conveyor.getSourceLocationId(), conveyor.getTargetLocationId());
                        if (manageLogic) {
                            itemMovementProcessor.recalculateConveyorAccumulation(e.getEntityId());
                            if (oldSpeed <= 0 && e.getSpeed() > 0) {
                                itemMovementProcessor.wakeUpPrecedingConveyors(conveyor.getSourceLocationId());
                            }
                        }
                        if (shouldBroadcast)
                            webSocketService.broadcastConnectionUpdated(
                                    new UpdateModel(conveyor.getId(), Map.of("speed", e.getSpeed())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ChuteEmptyEvent e -> {
                    Set<String> items = Optional.ofNullable(liveLocationRepository.getItemsAtLocation(e.getEntityId()))
                            .orElseGet(Set::of);
                    long removedItems = 0;
                    for (String item : items) {
                        RedisLiveItem itemState = liveItemRepository.getItemState(item);
                        operationalAnalyticsService.recordSuccessfulExit(e, e.getEntityId(), itemState, item);
                        String positionId = itemState != null && itemState.getPositionId() != null
                                ? itemState.getPositionId()
                                : e.getEntityId();
                        PositionType positionType = itemState != null && itemState.getType() != null
                                ? itemState.getType()
                                : PositionType.LOCATION;
                        recordPathTraversal(new PathTraversedEvent(
                                item,
                                positionId,
                                positionType,
                                positionId,
                                positionType,
                                List.of(positionId),
                                e.getTimestamp()));
                        liveLocationRepository.removeItemFromLocation(e.getEntityId(), item);
                        liveItemRepository.deleteItem(item);
                        removedItems++;
                    }
                    if (shouldBroadcast)
                        webSocketService.broadcastChuteEmptied(e.getEntityId(), e.getTimestamp());

                    retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                    if (manageLogic) {
                        itemMovementProcessor.wakeUpPrecedingConveyors(e.getEntityId());
                    }
                    yield Map.of(
                            "status", "PROCESSED_SUCCESSFULLY",
                            "itemsExited", removedItems);
                }

                case ConnectionLengthChangedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    checkpointItems(e.getEntityId(), conveyor.getSpeed(), e.getTimestamp());
                    conveyor.setLength(e.getLength());
                    conveyorService.updateConveyor(conveyor);
                    if (manageLogic) {
                        itemMovementProcessor.recalculateConveyorAccumulation(e.getEntityId());
                    }

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("length", e.getLength())), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionPropertiesUpdatedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());

                    conveyor.setProperties(e.getUpdatedProperties());
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        DisplayRuleVisualStyle style = this.displayRulesService.applyDisplayRules(
                                conveyorRootFields(conveyor),
                                conveyor.getProperties(), this.displayRulesService.getDisplayRules());

                        Map<String, Object> updates = new HashMap<>();
                        updates.put("properties", e.getUpdatedProperties());
                        updates.put("customColor", style != null ? style.getFillColor() : null);

                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), updates),
                                e.getTimestamp());
                    }

                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionActivatedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    boolean wasActive = conveyor.isActive();
                    conveyor.setOperatorEnabled(true);
                    conveyor.setActive(!hasStoppingAlarm(conveyor));
                    conveyorService.updateConveyor(conveyor);
                    operationalAnalyticsService.recordSimulationConnectionSignal(
                            e, conveyor.getId(), conveyor.isActive(), null,
                            conveyor.getSourceLocationId(), conveyor.getTargetLocationId());
                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("operatorEnabled", true);
                        updates.put("active", conveyor.isActive());
                        updates.put("activeAlarms", conveyor.getActiveAlarms());
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                            "effectiveActivityChanged", wasActive != conveyor.isActive());
                }

                case ConnectionDeactivatedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    boolean wasActive = conveyor.isActive();
                    conveyor.setOperatorEnabled(false);
                    conveyor.setActive(false);
                    conveyorService.updateConveyor(conveyor);
                    operationalAnalyticsService.recordSimulationConnectionSignal(
                            e, conveyor.getId(), false, null,
                            conveyor.getSourceLocationId(), conveyor.getTargetLocationId());
                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("operatorEnabled", false);
                        updates.put("active", false);
                        updates.put("activeAlarms", conveyor.getActiveAlarms());
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                            "effectiveActivityChanged", wasActive);
                }

                case AlarmRaisedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getConveyorId());
                    boolean wasActive = conveyor.isActive();
                    ActiveAlarm existing = conveyor.getActiveAlarms().stream()
                            .filter(alarm -> alarm.getAlarmId().equals(e.getAlarmId()))
                            .findFirst()
                            .orElse(null);
                    if (existing != null) {
                        if (existing.getSeverity() != e.getSeverity()
                                || existing.isStopsConveyor() != e.isStopsConveyor()
                                || !Objects.equals(existing.getTypology(), e.getTypology())) {
                            throw new IllegalStateException(
                                    "Alarm id is already active with different metadata: " + e.getAlarmId());
                        }
                        yield Map.of("status", "IGNORED_DUPLICATE",
                                "effectiveActivityChanged", false);
                    }
                    conveyor.getActiveAlarms().add(new ActiveAlarm(
                            e.getAlarmId(), e.getConveyorId(), e.getSeverity(), e.getTypology(),
                            e.isStopsConveyor(), e.getTimestamp()));
                    conveyor.setActive(conveyor.isOperatorEnabled() && !hasStoppingAlarm(conveyor));
                    conveyorService.updateConveyor(conveyor);
                    if (shouldBroadcast) {
                        broadcastConveyorAlarmState(conveyor, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                            "effectiveActivityChanged", wasActive != conveyor.isActive());
                }

                case AlarmClearedEvent e -> {
                    var conveyor = conveyorService.getConveyorById(e.getConveyorId());
                    boolean wasActive = conveyor.isActive();
                    boolean removed = conveyor.getActiveAlarms()
                            .removeIf(alarm -> alarm.getAlarmId().equals(e.getAlarmId()));
                    if (!removed) {
                        yield Map.of("status", "IGNORED_DUPLICATE",
                                "effectiveActivityChanged", false);
                    }
                    conveyor.setActive(conveyor.isOperatorEnabled() && !hasStoppingAlarm(conveyor));
                    conveyorService.updateConveyor(conveyor);
                    if (shouldBroadcast) {
                        broadcastConveyorAlarmState(conveyor, e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY",
                            "effectiveActivityChanged", wasActive != conveyor.isActive());
                }

                case ComponentAlarmRaisedEvent e -> reduceComponentAlarmRaised(e, shouldBroadcast);

                case ComponentAlarmClearedEvent e -> reduceComponentAlarmCleared(e, shouldBroadcast);

                case AnomalyEvaluationTickEvent e -> {
                    AnomalyProcessingMode analyticsMode = AnomalyProcessingContext.getMode();
                    if (analyticsMode == null) {
                        String simulationId = DatabaseContextHolder.getSimulationId();
                        analyticsMode = simulationId == null
                                ? AnomalyProcessingMode.LIVE
                                : e.getTimestamp().isAfter(timeService.physicalNow())
                                        ? AnomalyProcessingMode.FUTURE_SIMULATION
                                        : AnomalyProcessingMode.HISTORICAL_PLAYBACK;
                    }
                    anomalyEngine.evaluate(e, analyticsMode);
                    simulationService.scheduleNextAnomalyTick(e);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ReleaseStagingConveyorEvent e -> {
                    Conveyor conveyor = conveyorService.getConveyorById(e.getEntityId());
                    if (conveyor.getType() != flunav.types.ConveyorType.STAGING) {
                        throw new IllegalStateException("Conveyor is not a staging conveyor: " + e.getEntityId());
                    }
                    if (!conveyor.isActive()) {
                        throw new IllegalStateException("Staging conveyor is inactive: " + e.getEntityId());
                    }
                    if (conveyor.getSpeed() == null || conveyor.getSpeed() <= 0.0) {
                        throw new IllegalStateException("Staging conveyor speed must be positive: " + e.getEntityId());
                    }
                    yield itemMovementProcessor.releaseStagingConveyor(conveyor, e.getTimestamp(), shouldBroadcast);
                }

                case ConnectionTypeChangedEvent e -> {
                    Conveyor conveyor = conveyorService.getConveyorById(e.getEntityId());
                    flunav.types.ConveyorType oldType = conveyor.getType();
                    if (oldType == e.getConveyorType()) {
                        yield Map.of("status", "IGNORED_DUPLICATE");
                    }
                    if (oldType == flunav.types.ConveyorType.STAGING) {
                        itemMovementProcessor.checkpointStagingItems(conveyor, e.getTimestamp(), true,
                                shouldBroadcast);
                    } else {
                        checkpointItems(conveyor.getId(), conveyor.getSpeed(), e.getTimestamp());
                    }
                    conveyor.setType(e.getConveyorType());
                    if (e.getConveyorType() == flunav.types.ConveyorType.STAGING
                            && conveyor.getMinDistance() == null) {
                        conveyor.setMinDistance(0.1);
                    }
                    conveyorService.updateConveyor(conveyor);
                    if (e.getConveyorType() == flunav.types.ConveyorType.STAGING) {
                        itemMovementProcessor.checkpointStagingItems(conveyor, e.getTimestamp(), true,
                                shouldBroadcast);
                    } else if (manageLogic) {
                        itemMovementProcessor.recalculateConveyorAccumulation(conveyor.getId());
                    }
                    if (shouldBroadcast) {
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("conveyorType", conveyor.getType());
                        updates.put("minDistance", conveyor.getMinDistance());
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), updates), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case LocationAddToMainPath e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setMainPath(true);
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("isMainPath", true)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionRemoveFromMainPath e -> {
                    var conveyor = conveyorService.getConveyorById(e.getEntityId());
                    conveyor.setMainPath(false);
                    conveyorService.updateConveyor(conveyor);

                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionUpdated(
                                new UpdateModel(conveyor.getId(), Map.of("isMainPath", false)), e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case ConnectionDeletedEvent e -> {
                    Set<String> removedConveyorIds = topologyProvider.getAllConveyors().stream()
                            .filter(conveyor -> e.getSourceLocationId().equals(conveyor.getSourceLocationId())
                                    && e.getTargetLocationId().equals(conveyor.getTargetLocationId()))
                            .map(Conveyor::getId)
                            .collect(java.util.stream.Collectors.toSet());
                    String removedConveyorId = removedConveyorIds.stream().sorted().findFirst().orElse(null);
                    removeHotItemsAtPositions(removedConveyorIds, e.getTimestamp(), shouldBroadcast);
                    conveyorService.deleteConveyor(e.getSourceLocationId(), e.getTargetLocationId());
                    operationalAnalyticsService.recordSimulationConnectionSignal(
                            e, removedConveyorId, null, null,
                            e.getSourceLocationId(), e.getTargetLocationId());
                    if (shouldBroadcast) {
                        webSocketService.broadcastConnectionDeleted(e.getSourceLocationId(), e.getTargetLocationId(),
                                e.getTimestamp());
                    }
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                case PathTraversedEvent e -> {
                    recordPathTraversal(e);
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }

                default -> {
                    logger.debug("Event type {} handled by fallback logic or ignored",
                            event.getClass().getSimpleName());
                    yield Map.of("status", "PROCESSED_SUCCESSFULLY");
                }
            };
            invalidatePathCacheAfterTopologyChange(event, result);
            anomalyObservationService.collectTopologyChange(event);
            if (shouldBroadcast) {
                throughputBucketService.recordSuccessfulReduction(event, result);
            }
            return result;
        });
    }

    private Map<String, Object> reduceComponentAlarmRaised(ComponentAlarmRaisedEvent event,
            boolean shouldBroadcast) {
        if (event.getComponentType() == flunav.types.ComponentType.CONVEYOR) {
            Conveyor conveyor = conveyorService.getConveyorById(event.getComponentId());
            ActiveAlarm existing = conveyor.getActiveAlarms().stream()
                    .filter(alarm -> alarm.getAlarmId().equals(event.getAlarmId())).findFirst().orElse(null);
            if (existing != null) {
                return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
            }
            boolean wasActive = conveyor.isActive();
            conveyor.getActiveAlarms().add(new ActiveAlarm(event.getAlarmId(), event.getComponentId(),
                    event.getFindingId(), event.getComponentType(), event.getSeverity(), event.getTypology(),
                    event.getSource(), event.isStopsComponent(), event.getTimestamp()));
            conveyor.setActive(conveyor.isOperatorEnabled() && !hasStoppingAlarm(conveyor));
            conveyorService.updateConveyor(conveyor);
            if (shouldBroadcast) {
                broadcastConveyorAlarmState(conveyor, event.getTimestamp());
            }
            return Map.of("status", "PROCESSED_SUCCESSFULLY",
                    "effectiveActivityChanged", wasActive != conveyor.isActive());
        }

        Location location = locationService.getLocationById(event.getComponentId());
        if (location.getActiveAlarms().stream().anyMatch(alarm -> alarm.getAlarmId().equals(event.getAlarmId()))) {
            return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
        }
        location.getActiveAlarms().add(new ActiveAlarm(event.getAlarmId(), event.getComponentId(),
                event.getFindingId(), event.getComponentType(), event.getSeverity(), event.getTypology(),
                event.getSource(), event.isStopsComponent(), event.getTimestamp()));
        locationService.fullUpdateLocation(location);
        if (shouldBroadcast) {
            webSocketService.broadcastLocationPropertiesUpdated(
                    new UpdateModel(location.getId(), Map.of("activeAlarms", location.getActiveAlarms())),
                    event.getTimestamp());
        }
        return Map.of("status", "PROCESSED_SUCCESSFULLY", "effectiveActivityChanged", false);
    }

    private Map<String, Object> reduceComponentAlarmCleared(ComponentAlarmClearedEvent event,
            boolean shouldBroadcast) {
        if (event.getComponentType() == flunav.types.ComponentType.CONVEYOR) {
            Conveyor conveyor = conveyorService.getConveyorById(event.getComponentId());
            boolean wasActive = conveyor.isActive();
            boolean removed = conveyor.getActiveAlarms().removeIf(alarm -> alarm.getAlarmId().equals(event.getAlarmId()));
            if (!removed) {
                return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
            }
            conveyor.setActive(conveyor.isOperatorEnabled() && !hasStoppingAlarm(conveyor));
            conveyorService.updateConveyor(conveyor);
            if (shouldBroadcast) {
                broadcastConveyorAlarmState(conveyor, event.getTimestamp());
            }
            return Map.of("status", "PROCESSED_SUCCESSFULLY",
                    "effectiveActivityChanged", wasActive != conveyor.isActive());
        }

        Location location = locationService.getLocationById(event.getComponentId());
        boolean removed = location.getActiveAlarms().removeIf(alarm -> alarm.getAlarmId().equals(event.getAlarmId()));
        if (!removed) {
            return Map.of("status", "IGNORED_DUPLICATE", "effectiveActivityChanged", false);
        }
        locationService.fullUpdateLocation(location);
        if (shouldBroadcast) {
            webSocketService.broadcastLocationPropertiesUpdated(
                    new UpdateModel(location.getId(), Map.of("activeAlarms", location.getActiveAlarms())),
                    event.getTimestamp());
        }
        return Map.of("status", "PROCESSED_SUCCESSFULLY", "effectiveActivityChanged", false);
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

    private boolean hasStoppingAlarm(Conveyor conveyor) {
        return conveyor.getActiveAlarms() != null
                && conveyor.getActiveAlarms().stream().anyMatch(ActiveAlarm::isStopsConveyor);
    }

    private void broadcastConveyorAlarmState(Conveyor conveyor, Instant timestamp) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("active", conveyor.isActive());
        updates.put("operatorEnabled", conveyor.isOperatorEnabled());
        updates.put("activeAlarms", conveyor.getActiveAlarms());
        webSocketService.broadcastConnectionUpdated(new UpdateModel(conveyor.getId(), updates), timestamp);
    }

    private List<String> alarmAffectedItems(DomainEvent event) {
        String componentId;
        boolean stops;
        if (event instanceof AlarmRaisedEvent raised) {
            componentId = raised.getConveyorId();
            stops = raised.isStopsConveyor();
        } else if (event instanceof ComponentAlarmRaisedEvent raised) {
            componentId = raised.getComponentId();
            stops = raised.isStopsComponent();
        } else {
            return List.of();
        }
        if (!stops) {
            return List.of();
        }
        return liveItemRepository.getAllActiveItems().stream()
                .filter(Objects::nonNull)
                .filter(item -> componentId.equals(item.getPositionId())
                        || (item.getPath() != null && item.getPath().contains(componentId)))
                .map(RedisLiveItem::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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

    /**
     * Checkpoints all items on a conveyor before a speed-changing event takes
     * effect. The delegated processor stores distance in Redis so rescheduling uses
     * physical progress instead of stale percentages.
     */
    private void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        itemMovementProcessor.checkpointItems(edgeId, oldSpeed, timestamp);
    }

    /**
     * Removes hot positions and movement schedules made invalid by topology
     * deletion. The surrounding topology event remains the replayable cause, while
     * durable item metadata is retained for history and later repositioning.
     */
    private void removeHotItemsAtPositions(Set<String> positionIds, Instant timestamp, boolean shouldBroadcast) {
        if (positionIds == null || positionIds.isEmpty()) {
            return;
        }
        for (RedisLiveItem item : liveItemRepository.getAllActiveItems()) {
            if (item == null || !positionIds.contains(item.getPositionId())) {
                continue;
            }
            itemMovementProcessor.cancelScheduledEvent(item.getId());
            liveItemRepository.deleteItem(item.getId());
            if (shouldBroadcast) {
                webSocketService.broadcastPositionLost(item.getId(), timestamp);
            }
        }
    }

    /**
     * Records live conveyor transit facts after the movement reducer succeeds.
     * The raw fact is enough for ClickHouse views to maintain path timing baselines
     * without replaying the full event stream for every analytics refresh.
     */
    private void recordCompletedTransit(
            ItemPositionChangedEvent event,
            RedisLiveItem previousState,
            PositionType newPositionType) {
        if (previousState == null
                || previousState.getEntryTime() == null
                || previousState.getPositionId() == null
                || previousState.getType() != PositionType.CONVEYOR) {
            return;
        }

        Conveyor previousConveyor;
        try {
            previousConveyor = topologyProvider.getConveyorById(previousState.getPositionId());
        } catch (Exception e) {
            logger.debug("Skipping transit metric for missing conveyor {}", previousState.getPositionId());
            return;
        }
        if (previousConveyor == null
                || previousConveyor.getSourceLocationId() == null
                || previousConveyor.getTargetLocationId() == null) {
            return;
        }

        long transitTimeMillis = Duration.between(previousState.getEntryTime(), event.getTimestamp()).toMillis();
        if (transitTimeMillis < 0L) {
            logger.debug("Skipping transit metric with negative duration for item {}", event.getEntityId());
            return;
        }

        clickHouseService.saveLocationTransitMetricAsync(new LocationTransitMetric(
                event.getTimestamp(),
                currentSimulationScope(),
                event.getEntityId(),
                previousConveyor.getSourceLocationId(),
                previousConveyor.getTargetLocationId(),
                previousState.getPositionId(),
                previousState.getType(),
                event.getLocationId(),
                newPositionType,
                transitTimeMillis,
                List.of(previousConveyor.getSourceLocationId(), previousConveyor.getTargetLocationId())));
    }

    /**
     * Records a path-assignment fact from the item's current hot position.
     * The analytics event is reduced in-thread so simulation ThreadLocal context is
     * captured before any async queue flush can occur.
     */
    private void recordCurrentPathTraversal(String itemId, List<String> path, Instant timestamp) {
        if (path == null) {
            return;
        }

        RedisLiveItem itemState = liveItemRepository.getItemState(itemId);
        if (itemState == null || itemState.getPositionId() == null) {
            return;
        }

        PositionType positionType = itemState.getType() != null ? itemState.getType() : PositionType.LOCATION;
        recordPathTraversal(new PathTraversedEvent(
                itemId,
                itemState.getPositionId(),
                positionType,
                itemState.getPositionId(),
                positionType,
                path,
                timestamp));
    }

    /**
     * Writes path traversal analytics without adding analytics-only facts to the
     * replay event store or crossing RabbitMQ boundaries.
     */
    private void recordPathTraversal(PathTraversedEvent event) {
        clickHouseService.savePathTraversalMetricAsync(event);
    }

    private String currentSimulationScope() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId != null ? simulationId : "live";
    }

    /**
     * Resolves destinations and an initial route for a newly created item.
     * Explicit destinations win; otherwise event fields are evaluated against the
     * destination mapping table at the item's domain timestamp.
     */
    private AppliedDestination applyDestinationToCreatedItem(ItemInput item, Instant timestamp) {
        List<String> explicitDestinations = normalizeDestinations(item.getDestinations());
        List<String> destinations = !explicitDestinations.isEmpty()
                ? explicitDestinations
                : destinationMappingService.resolveDestinations(itemRootFields(item), item.getProperties(), timestamp);
        item.setDestinations(destinations);
        if (destinations.isEmpty()) {
            item.setSelectedExitId(null);
            item.setPath(null);
            item.setRoutingStatus(RoutingStatus.UNROUTED);
            item.setRoutingStatusUpdatedAt(timestamp);
            return new AppliedDestination(destinations, null, RoutingStatus.UNROUTED, null);
        }
        if (item.getLocationId() == null) {
            item.setRoutingStatus(RoutingStatus.UNROUTED);
            item.setRoutingStatusUpdatedAt(timestamp);
            return new AppliedDestination(destinations, null, RoutingStatus.UNROUTED, null);
        }

        PositionType positionType = (item.getPositionType() != null) ? item.getPositionType() : PositionType.LOCATION;
        DestinationMappingService.RushPriority initialRush = destinationMappingService.evaluateRush(
                itemRootFields(item), item.getProperties(), destinations, item.getPriority(), timestamp);
        com.flunav.backend.domain.Item routingItem = new com.flunav.backend.domain.Item(
                item.getId(), item.getName(), Boolean.TRUE.equals(item.getActive()), initialRush.effectivePriority(),
                item.getProperties());
        routingItem.setDestinations(destinations);
        RoutingDecisionService.RoutingDecision decision = routingDecisionService.selectRoute(
                routingItem, item.getLocationId(), positionType, timestamp);
        item.setSelectedExitId(decision.selectedExitId());
        item.setRoutingStatus(decision.routingStatus());
        item.setRoutingStatusUpdatedAt(timestamp);
        item.setPath(decision.path());
        return new AppliedDestination(destinations, decision.selectedExitId(), decision.routingStatus(),
                decision.path());
    }

    /**
     * Publishes the operational command that tells external equipment the selected
     * exit. Simulation and non-broadcast reductions skip this because command
     * messages are live side effects, not replayable domain history.
     */
    private void publishDestinationCommandIfNeeded(ItemCreatedEvent event, ItemInput item,
            AppliedDestination appliedDestination, boolean shouldBroadcast) {
        if (!manageLogic || !shouldBroadcast || DatabaseContextHolder.getSimulationId() != null) {
            return;
        }
        if (appliedDestination.selectedExitId() == null
                || !Objects.equals(item.getSelectedExitId(), appliedDestination.selectedExitId())) {
            return;
        }

        try {
            amqpTemplate.convertAndSend(commandsQueue,
                    new ItemDestinationEvent(event.getEntityId(), appliedDestination.selectedExitId(),
                            event.getTimestamp()));
        } catch (Exception e) {
            logger.error("Failed to publish destination command for item {}", event.getEntityId(), e);
        }
    }

    /**
     * Reattempts route assignment for priority items waiting on chute capacity.
     * The routing lock serializes retries so newly opened capacity is assigned in
     * waiting order without overbooking projected occupancy.
     */
    private void retryWaitingHighPriorityItems(Instant timestamp, boolean shouldBroadcast) {
        routingCoordinator.withRoutingLock(() -> {
            liveItemRepository.getAllActiveItems().stream()
                    .filter(item -> item != null)
                    .filter(item -> item.getRoutingStatus() == RoutingStatus.WAITING_FOR_CAPACITY)
                    .sorted(Comparator
                            .comparing(com.flunav.backend.models.RedisLiveItem::getRoutingStatusUpdatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(com.flunav.backend.models.RedisLiveItem::getEntryTime,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(com.flunav.backend.models.RedisLiveItem::getId))
                    .forEach(waitingState -> {
                        var item = itemService.getItemById(waitingState.getId());
                        if (item == null
                                || destinationMappingService.evaluateRush(item, timestamp).effectivePriority() <= 0.0) {
                            return;
                        }
                        PositionType positionType = item.getPositionType() != null
                                ? item.getPositionType()
                                : PositionType.LOCATION;
                        RoutingDecisionService.RoutingDecision decision = routingDecisionService.selectRoute(
                                item, item.getPositionId(), positionType, timestamp);

                        boolean changed = !Objects.equals(waitingState.getSelectedExitId(), decision.selectedExitId())
                                || !Objects.equals(waitingState.getPath(), decision.path())
                                || waitingState.getRoutingStatus() != decision.routingStatus();
                        if (!changed) {
                            return;
                        }

                        itemService.updateItemRouting(
                                item.getId(), item.getDestinations(), decision.selectedExitId(),
                                decision.routingStatus(), timestamp, decision.path());
                        operationalAnalyticsService.recordRecirculation(
                                item.getId(), waitingState.getPath(), decision.path(), timestamp);
                        pathAssignmentPublisher.publishIfAssigned(
                                item.getId(),
                                decision.selectedExitId(),
                                decision.routingStatus(),
                                decision.path(),
                                timestamp,
                                shouldBroadcast);

                        if (shouldBroadcast) {
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("selectedExitId", decision.selectedExitId());
                            updates.put("routingStatus", decision.routingStatus());
                            updates.put("routingStatusUpdatedAt", timestamp);
                            updates.put("path", decision.path());
                            addRushFields(updates, item, timestamp);
                            webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), timestamp);
                        }
                    });
            return null;
        });
    }

    /**
     * Normalizes externally supplied destination ids while preserving order.
     * Blank values are rejected before they can enter Redis routing state.
     */
    private List<String> normalizeDestinations(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String destination : destinations) {
            if (destination == null || destination.isBlank()) {
                throw new IllegalArgumentException("destinations must contain nonblank values");
            }
            normalized.add(destination.trim());
        }
        return List.copyOf(normalized);
    }

    private record AppliedDestination(
            List<String> destinations,
            String selectedExitId,
            RoutingStatus routingStatus,
            List<String> path) {
    }

    /**
     * Validates the normalized priority score accepted by routing.
     * The 0.0 to 1.0 range is used directly by capacity and travel-time scoring.
     */
    private void validatePriority(Double priority) {
        if (priority == null || !Double.isFinite(priority) || priority < 0.0 || priority > 1.0) {
            throw new IllegalArgumentException("priority must be finite and between 0.0 and 1.0");
        }
    }

    /**
     * Prevents custom properties from redefining top-level item priority.
     * Priority is a first-class routing field, so allowing a duplicate property
     * would make display rules and route selection disagree.
     */
    private void validateItemProperties(Map<String, Object> properties) {
        if (properties != null
                && properties.keySet().stream().anyMatch(key -> key != null && key.equalsIgnoreCase("priority"))) {
            throw new IllegalArgumentException("priority is a top-level item field");
        }
    }

    /**
     * Builds the top-level fields used by destination mapping on item creation.
     * These fields take precedence over custom properties during rule evaluation.
     */
    private Map<String, Object> itemRootFields(ItemInput item) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("speed", item.getSpeed());
        fields.put("priority", item.getPriority());
        fields.put("active", item.getActive());
        fields.put("locationId", item.getLocationId());
        fields.put("positionType", item.getPositionType());
        fields.put("progress", item.getProgress());
        fields.put("destinations", item.getDestinations());
        fields.put("timestamp", item.getTimestamp());
        return fields;
    }

    /**
     * Builds the top-level fields used by mapping or display logic for saved items.
     * Routing state comes from Redis overlay data on the domain item.
     */
    private Map<String, Object> itemRootFields(com.flunav.backend.domain.Item item) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("active", item.isActive());
        fields.put("priority", item.getPriority());
        fields.put("positionId", item.getPositionId());
        fields.put("positionType", item.getPositionType());
        fields.put("entryTimestamp", item.getEntryTimestamp());
        fields.put("routingStatus", item.getRoutingStatus());
        return fields;
    }

    private void addRushFields(Map<String, Object> updates, com.flunav.backend.domain.Item item, Instant timestamp) {
        DestinationMappingService.RushPriority rush = destinationMappingService.evaluateRush(item, timestamp);
        updates.put("effectivePriority", rush.effectivePriority());
        updates.put("rushActive", rush.rushActive());
    }

    /**
     * Builds the top-level fields used when display rules target locations.
     * These values are topology metadata rather than transient Redis occupancy.
     */
    private Map<String, Object> locationRootFields(com.flunav.backend.domain.Location location) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", location.getId());
        fields.put("name", location.getName());
        fields.put("type", location.getType());
        fields.put("active", location.getActive());
        fields.put("latitude", location.getLatitude());
        fields.put("longitude", location.getLongitude());
        fields.put("capacity", location.getCapacity());
        fields.put("timeToProcessMs", location.getTimeToProcessMs());
        return fields;
    }

    /**
     * Builds the top-level fields used when display rules target conveyors.
     * Conveyor speed, capacity, and main-path flags are included because they drive
     * routing and movement behavior.
     */
    private Map<String, Object> conveyorRootFields(Conveyor conveyor) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", conveyor.getId());
        fields.put("sourceId", conveyor.getSourceLocationId());
        fields.put("targetId", conveyor.getTargetLocationId());
        fields.put("length", conveyor.getLength());
        fields.put("speed", conveyor.getSpeed());
        fields.put("minDistance", conveyor.getMinDistance());
        fields.put("type", conveyor.getType());
        fields.put("active", conveyor.isActive());
        fields.put("mainPath", conveyor.isMainPath());
        fields.put("capacity", conveyor.getCapacity());
        return fields;
    }

    /**
     * Builds top-level fields for a connection before it exists as a conveyor.
     * Creation events use this shape so destination/display rule evaluation can
     * inspect the requested edge attributes.
     */
    private Map<String, Object> connectionRootFields(ConnectionCreatedEvent event) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", event.getConnectionId());
        fields.put("sourceId", event.getSourceId());
        fields.put("targetId", event.getTargetId());
        fields.put("name", event.getName());
        fields.put("length", event.getLength());
        fields.put("speed", event.getSpeed());
        fields.put("minDistance", event.getMinDistance());
        fields.put("type", event.getType());
        fields.put("active", event.getIsActive());
        fields.put("mainPath", event.getMainPath());
        fields.put("capacity", event.getCapacity());
        return fields;
    }
}
