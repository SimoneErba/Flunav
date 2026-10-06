package com.flunav.backend.services;

import com.flunav.backend.utils.SimulationRunTiming;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Item;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.context.DatabaseContextHolder;
import flunav.events.DomainEvent;
import flunav.events.ItemProcessingCompletedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.events.ItemMovementCheckEvent;
import flunav.events.ItemExitedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import flunav.types.ConveyorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies item physics and schedules the next movement-domain event.
 *
 * This service deliberately uses the same code in live and simulation contexts.
 * {@link #scheduleEvent(DomainEvent)} is the boundary that selects the live system
 * scheduler or the current simulation's internal queue. Routing is recalculated
 * only at decision points or when an assignment becomes unusable.
 */
@Service
public class ItemMovementProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ItemMovementProcessor.class);

    private final AmqpTemplate amqpTemplate;
    private final String itemEventsRoutingKey;
    private final LiveConveyorRepository liveConveyorRepository;
    private final LiveItemRepository liveItemRepository;
    private final LiveLocationRepository liveLocationRepository;
    private final TopologyProvider topologyProvider;
    private final ItemService itemService;
    private final RoutingDecisionService routingDecisionService;
    private final RoutingCoordinator routingCoordinator;
    private final SimulationService simulationService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final TimeService timeService;
    private final PathAssignmentPublisher pathAssignmentPublisher;
    private final WebSocketService webSocketService;
    private final OperationalAnalyticsService operationalAnalyticsService;
    private final MultiSimulationMetricsService multiSimulationMetricsService;
    private final boolean manageLogic;
    private final double averageItemLengthMeters;
    private final Object[] admissionLocks = new Object[256];

    public ItemMovementProcessor(
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.routing-key.item-events}") String itemEventsRoutingKey,
            LiveConveyorRepository liveConveyorRepository,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            TopologyProvider topologyProvider,
            ItemService itemService,
            RoutingDecisionService routingDecisionService,
            RoutingCoordinator routingCoordinator,
            @Lazy SimulationService simulationService,
            LiveSystemScheduler liveSystemScheduler,
            TimeService timeService,
            PathAssignmentPublisher pathAssignmentPublisher,
            WebSocketService webSocketService,
            OperationalAnalyticsService operationalAnalyticsService,
            MultiSimulationMetricsService multiSimulationMetricsService,
            @Value("${simulation.manage-logic:true}") boolean manageLogic,
            @Value("${average-item-length-cm:15}") double averageItemLengthCm) {
        this.amqpTemplate = amqpTemplate;
        this.itemEventsRoutingKey = itemEventsRoutingKey;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.topologyProvider = topologyProvider;
        this.itemService = itemService;
        this.routingDecisionService = routingDecisionService;
        this.routingCoordinator = routingCoordinator;
        this.simulationService = simulationService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.timeService = timeService;
        this.pathAssignmentPublisher = pathAssignmentPublisher;
        this.webSocketService = webSocketService;
        this.operationalAnalyticsService = operationalAnalyticsService;
        this.multiSimulationMetricsService = multiSimulationMetricsService;
        this.manageLogic = manageLogic;
        this.averageItemLengthMeters = averageItemLengthCm / 100.0;
        for (int index = 0; index < admissionLocks.length; index++) admissionLocks[index] = new Object();
    }

    /**
     * Publishes externally ingested item events into the live processing queue.
     * Failures are logged here because controllers and schedulers should not lose
     * the event type context when RabbitMQ publication fails.
     */
    public void publishEvent(DomainEvent event) {
        try {
            amqpTemplate.convertAndSend(itemEventsRoutingKey, event);
        } catch (Exception e) {
            logger.error("Failed to publish event of type {}", event.getEventType(), e);
        }
    }

    /**
     * Freezes every item on a conveyor at the supplied timestamp before speed
     * changes are applied. This preserves percentage progress so rescheduled
     * movement starts from the same conveyor position.
     */
    public void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        var allItems = liveConveyorRepository.getItemsOrderedByDistance(edgeId);

        for (var itemId : allItems) {
            var itemData = liveItemRepository.getItemState(itemId);
            if (itemData == null || itemData.isMovementPaused() || itemData.isFlowPaused()
                    || liveConveyorRepository.isFlowStopped(edgeId)) {
                continue;
            }
            var lastUpdateTime = itemData.getEntryTime();
            double storedProgress = itemData.getAccumulatedDistance();

            if (lastUpdateTime != null) {
                long timeElapsed = timestamp.toEpochMilli() - lastUpdateTime.toEpochMilli();
                Conveyor conveyor = topologyProvider.getConveyorById(edgeId);
                double progressDelta = conveyor.getLength() > 0
                        ? (timeElapsed / 1000.0) * oldSpeed / conveyor.getLength() * 100.0
                        : 0.0;
                double progress = storedProgress + progressDelta;
                liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
            }
        }
    }

    /** Keeps each item's traveled distance stable when a conveyor changes length. */
    public void rescaleProgressForLengthChange(String conveyorId, double oldLength, double newLength,
            Instant timestamp) {
        if (oldLength <= 0 || newLength <= 0) {
            return;
        }
        for (String itemId : liveConveyorRepository.getItemsOrderedByDistance(conveyorId)) {
            var state = liveItemRepository.getItemState(itemId);
            if (state != null && state.getType() == PositionType.CONVEYOR
                    && conveyorId.equals(state.getPositionId())) {
                liveItemRepository.checkpointPhysics(itemId, timestamp,
                        state.getAccumulatedDistance() * oldLength / newLength);
            }
        }
    }

    /** Freezes one item's derived conveyor position at a domain-event timestamp. */
    public void checkpointItem(String itemId, Instant timestamp, boolean shouldBroadcast) {
        var state = liveItemRepository.getItemState(itemId);
        if (state == null || state.getType() != PositionType.CONVEYOR || state.getPositionId() == null
                || state.getEntryTime() == null) {
            return;
        }
        Conveyor conveyor = topologyProvider.getConveyorById(state.getPositionId());
        if (conveyor == null) {
            return;
        }
        if (conveyor.getType() == ConveyorType.STAGING) {
            checkpointStagingItems(conveyor, timestamp, false, shouldBroadcast);
            return;
        }

        double progress = state.getAccumulatedDistance();
        if (!state.isMovementPaused() && !state.isFlowPaused()
                && !liveConveyorRepository.isFlowStopped(conveyor.getId())
                && conveyor.isActive() && conveyor.getSpeed() > 0) {
            long elapsedMillis = Math.max(0L, timestamp.toEpochMilli() - state.getEntryTime().toEpochMilli());
            if (conveyor.getLength() > 0) {
                progress += elapsedMillis / 1000.0 * conveyor.getSpeed() / conveyor.getLength() * 100.0;
            }
        }
        progress = Math.min(100.0, Math.max(0.0, progress));
        liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
        if (shouldBroadcast) {
            webSocketService.broadcastPositionUpdate(itemId, conveyor.getId(), timestamp,
                    PositionType.CONVEYOR, progress);
        }
    }

    /** Restarts one activated item from its frozen hot-state checkpoint. */
    public void resumeItem(String itemId, Instant timestamp, boolean shouldBroadcast) {
        var state = liveItemRepository.getItemState(itemId);
        if (state == null || state.getPositionId() == null || state.getType() == null) {
            return;
        }
        liveItemRepository.setMovementPaused(itemId, false);
        liveItemRepository.clearPlannedTransition(itemId);

        if (state.getType() == PositionType.LOCATION) {
            processLocationEntry(itemId, state.getPositionId(), timestamp, shouldBroadcast);
            return;
        }

        Conveyor conveyor = topologyProvider.getConveyorById(state.getPositionId());
        if (conveyor == null) {
            return;
        }
        double progress = Math.min(100.0, Math.max(0.0, state.getAccumulatedDistance()));
        liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
        if (shouldBroadcast) {
            webSocketService.broadcastPositionUpdate(itemId, conveyor.getId(), timestamp,
                    PositionType.CONVEYOR, progress);
        }
        if (conveyor.getType() != ConveyorType.STAGING && conveyor.isActive() && conveyor.getSpeed() > 0) {
            handleItemEntryToConveyor(itemId, conveyor.getId(), timestamp, progress, null, shouldBroadcast);
        }
    }

    /**
     * Schedules the next movement step after an item enters a conveyor.
     * The same method is used by live and simulation modes, while scheduleEvent
     * decides whether the resulting internal event belongs to the live scheduler or
     * the simulation queue.
     */
    public void handleItemEntryToConveyor(String itemId, String conveyorId, Instant timestamp, Double progress,
            String previousPosId) {
        handleItemEntryToConveyor(itemId, conveyorId, timestamp, progress, previousPosId, true);
    }

    /**
     * Projects the next movement event for an item currently on a conveyor.
     * The method handles stopped conveyors, blocked downstream segments, chute
     * capacity, and recirculation before choosing where to schedule the item next.
     */
    public void handleItemEntryToConveyor(String itemId, String conveyorId, Instant timestamp, Double progress,
            String previousPosId, boolean publishAssignments) {
        if (progress == null)
            progress = 0.0;
        var currentState = liveItemRepository.getItemState(itemId);
        if (currentState != null && currentState.isMovementPaused()) {
            cancelScheduledEvent(itemId);
            liveItemRepository.clearPlannedTransition(itemId);
            return;
        }
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null)
            return;

        if (conveyor.getType() == ConveyorType.STAGING || currentState == null) {
            cancelScheduledEvent(itemId);
            return;
        }
        if (!conveyor.isActive() || conveyor.getSpeed() == null || conveyor.getSpeed() <= 0
                || conveyor.getLength() == null || conveyor.getLength() <= 0) {
            cancelScheduledEvent(itemId);
            return;
        }
        if (liveConveyorRepository.isFlowStopped(conveyorId) && conveyor.getType() == ConveyorType.BELT) {
            return;
        }

        double currentMeters = conveyor.getLength() * progress / 100.0;
        String nextId = calculateNextConveyorIgnoringAvailability(itemId, conveyor.getTargetLocationId());
        boolean committed = currentState.getPlannedTransitionTimestamp() != null
                && Objects.equals(nextId, currentState.getPlannedPositionId());
        if (!committed && currentState.getPlannedTransitionTimestamp() != null) {
            liveItemRepository.clearPlannedTransition(itemId);
        }
        double stopMeters = committed ? conveyor.getLength() : mergeHoldingPosition(conveyor, itemId, nextId);
        if (conveyor.getType() == ConveyorType.ROLLER) {
            for (String otherId : liveConveyorRepository.getItemsOrderedByDistance(conveyorId)) {
                if (otherId.equals(itemId)) continue;
                var other = liveItemRepository.getItemState(otherId);
                if (other == null || !other.isFlowPaused()) continue;
                double otherMeters = conveyor.getLength() * other.getAccumulatedDistance() / 100.0;
                double gap = (itemLengthMeters(itemId) + itemLengthMeters(otherId)) / 2.0
                        + Math.max(0.0, Objects.requireNonNullElse(conveyor.getMinDistance(), 0.0));
                if (otherMeters >= currentMeters) stopMeters = Math.min(stopMeters, otherMeters - gap);
            }
        }
        long delayMillis = (long) Math.ceil(Math.max(0.0, stopMeters - currentMeters)
                / conveyor.getSpeed() * 1000.0);
        if (committed) {
            liveItemRepository.setPlannedTransition(itemId, nextId, PositionType.CONVEYOR,
                    timestamp.plusMillis(delayMillis));
        }
        scheduleMovementCheck(itemId, conveyorId, timestamp.plusMillis(delayMillis));
    }

    /** Schedules one replaceable admission check and stores it for recovery. */
    public void scheduleMovementCheck(String itemId, String conveyorId, Instant timestamp) {
        Instant scheduledAt = Instant.ofEpochMilli(timestamp.toEpochMilli());
        liveItemRepository.setMovementCheck(itemId, scheduledAt);
        scheduleEvent(new ItemMovementCheckEvent(itemId, conveyorId, scheduledAt));
    }

    /** Serializes admission with the state change so concurrent feeders see each other's transfers. */
    public void processMovementCheck(ItemMovementCheckEvent check, boolean shouldBroadcast,
            Consumer<ItemPositionChangedEvent> transfer) {
        var state = liveItemRepository.getItemState(check.getEntityId());
        if (state == null || state.getType() != PositionType.CONVEYOR
                || !check.getConveyorId().equals(state.getPositionId())
                || state.getMovementCheckTimestamp() == null
                || check.getTimestamp().toEpochMilli() != state.getMovementCheckTimestamp().toEpochMilli()
                || state.isMovementPaused()) return;
        Conveyor feeder = topologyProvider.getConveyorById(check.getConveyorId());
        if (feeder == null) return;
        var targetLocation = topologyProvider.getLocationById(feeder.getTargetLocationId());
        // Timed nodes own a dwell interval before selecting their outgoing conveyor.
        String nextId = targetLocation != null && targetLocation.getType() == LocationType.TIMED_NODE
                ? null : calculateNextConveyor(check.getEntityId(), feeder.getTargetLocationId(),
                        feeder.getId(), shouldBroadcast, check.getTimestamp());
        String lockKey = DatabaseContextHolder.getSimulationId() + ":" +
                (nextId != null ? nextId : feeder.getTargetLocationId());
        synchronized (admissionLocks[Math.floorMod(lockKey.hashCode(), admissionLocks.length)]) {
            state = liveItemRepository.getItemState(check.getEntityId());
            if (state == null || state.getMovementCheckTimestamp() == null
                    || check.getTimestamp().toEpochMilli() != state.getMovementCheckTimestamp().toEpochMilli()
                    || !feeder.getId().equals(state.getPositionId())) return;
            double position = projectedMeters(state, feeder, check.getTimestamp());
            double holdingPosition = mergeHoldingPosition(feeder, check.getEntityId(), nextId);
            boolean committed = nextId != null && nextId.equals(state.getPlannedPositionId())
                    && state.getPlannedTransitionTimestamp() != null;
            double safePosition = committed ? feeder.getLength() : holdingPosition;
            if (feeder.getType() == ConveyorType.ROLLER) {
                for (String otherId : liveConveyorRepository.getItemsOrderedByDistance(feeder.getId())) {
                    if (otherId.equals(check.getEntityId())) continue;
                    var other = liveItemRepository.getItemState(otherId);
                    if (other == null || !other.isFlowPaused()) continue;
                    double ahead = projectedMeters(other, feeder, check.getTimestamp());
                    double gap = (itemLengthMeters(check.getEntityId()) + itemLengthMeters(otherId)) / 2.0
                            + Math.max(0.0, Objects.requireNonNullElse(feeder.getMinDistance(), 0.0));
                    if (ahead >= position) safePosition = Math.min(safePosition, ahead - gap);
                }
            }
            if (position + 1e-9 < safePosition) {
                if (state.isFlowPaused()) {
                    liveItemRepository.setFlowPaused(check.getEntityId(), false);
                    liveItemRepository.checkpointPhysics(check.getEntityId(), check.getTimestamp(),
                            position / feeder.getLength() * 100.0);
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(new UpdateModel(check.getEntityId(),
                                Map.of("flowPaused", false)), check.getTimestamp());
                        webSocketService.broadcastPositionUpdate(check.getEntityId(), feeder.getId(),
                                check.getTimestamp(), PositionType.CONVEYOR,
                                position / feeder.getLength() * 100.0);
                    }
                }
                scheduleMovementCheck(check.getEntityId(), feeder.getId(), check.getTimestamp().plusMillis(
                        (long) Math.ceil((safePosition - position) / feeder.getSpeed() * 1000.0)));
                return;
            }
            boolean blocked = nextId != null && (!hasEntrySpace(nextId, check.getEntityId(), check.getTimestamp())
                    || !committed && holdingPosition < feeder.getLength()
                            && hasEarlierMergeArrival(feeder, nextId, state, check.getTimestamp()));
            if (nextId == null) {
                var target = topologyProvider.getLocationById(feeder.getTargetLocationId());
                blocked = target == null || (target.getType() == LocationType.CHUTE
                        && target.getCapacity() != null && target.getCapacity() > 0
                        && projectedChuteOccupancy(target.getId(), check.getEntityId()) >= target.getCapacity())
                        || (target != null && target.getType() != LocationType.TIMED_NODE
                        && !topologyProvider.getOutgoingConveyors(target.getId()).isEmpty());
            }
            if (safePosition + 1e-9 < holdingPosition || blocked || !feeder.isActive()) {
                pauseForFlow(check.getEntityId(), feeder, check.getTimestamp(),
                        Math.min(position, safePosition), shouldBroadcast);
                Instant retry = check.getTimestamp().plusMillis(1000);
                if (nextId != null) retry = predictedClearance(nextId, check.getEntityId(), check.getTimestamp());
                scheduleMovementCheck(check.getEntityId(), feeder.getId(), retry);
                return;
            }
            if (holdingPosition < feeder.getLength() && position + 1e-9 < feeder.getLength()) {
                Instant departure = check.getTimestamp().plusMillis((long) Math.ceil(
                        (feeder.getLength() - position) / feeder.getSpeed() * 1000.0));
                liveItemRepository.setPlannedTransition(check.getEntityId(), nextId, PositionType.CONVEYOR, departure);
                if (feeder.getType() == ConveyorType.BELT && liveConveyorRepository.isFlowStopped(feeder.getId())) {
                    liveConveyorRepository.setFlowStopped(feeder.getId(), false);
                    if (shouldBroadcast) webSocketService.broadcastConnectionUpdated(
                            new UpdateModel(feeder.getId(), Map.of("flowStopped", false)), check.getTimestamp());
                    resumeConveyorFromCheckpoints(feeder.getId(), check.getTimestamp(), shouldBroadcast);
                } else if (state.isFlowPaused()) {
                    liveItemRepository.setFlowPaused(check.getEntityId(), false);
                    liveItemRepository.checkpointPhysics(check.getEntityId(), check.getTimestamp(),
                            position / feeder.getLength() * 100.0);
                    if (shouldBroadcast) {
                        webSocketService.broadcastItemUpdated(new UpdateModel(check.getEntityId(),
                                Map.of("flowPaused", false)), check.getTimestamp());
                        webSocketService.broadcastPositionUpdate(check.getEntityId(), feeder.getId(),
                                check.getTimestamp(), PositionType.CONVEYOR, position / feeder.getLength() * 100.0);
                    }
                    if (feeder.getType() == ConveyorType.ROLLER) {
                        resumeRollerFollowers(feeder, check.getTimestamp(), shouldBroadcast);
                    }
                }
                scheduleMovementCheck(check.getEntityId(), feeder.getId(), departure);
                return;
            }
            liveItemRepository.setMovementCheck(check.getEntityId(), null);
            liveItemRepository.setFlowPaused(check.getEntityId(), false);
            if (shouldBroadcast) webSocketService.broadcastItemUpdated(
                    new UpdateModel(check.getEntityId(), Map.of("flowPaused", false)), check.getTimestamp());
            transfer.accept(new ItemPositionChangedEvent(check.getEntityId(),
                    nextId != null ? nextId : feeder.getTargetLocationId(),
                    nextId != null ? 0.0 : 100.0, check.getTimestamp()));
        }
    }

    /** Holds item centers before a shared junction while preserving travel through the final approach. */
    private double mergeHoldingPosition(Conveyor feeder, String itemId, String nextId) {
        if (nextId == null || topologyProvider.getAllConveyors().stream()
                .filter(candidate -> feeder.getTargetLocationId().equals(candidate.getTargetLocationId()))
                .limit(2).count() < 2) return feeder.getLength();
        return Math.max(0.0, feeder.getLength() - itemLengthMeters(itemId) / 2.0
                - Math.max(0.0, Objects.requireNonNullElse(feeder.getMinDistance(), 0.0)));
    }

    /** Reservations finish first; otherwise the head that reached its holding line first gets admission. */
    private boolean hasEarlierMergeArrival(Conveyor feeder, String nextId, RedisLiveItem current, Instant at) {
        Instant arrival = current.isFlowPaused() ? current.getEntryTime() : at;
        for (Conveyor otherFeeder : topologyProvider.getAllConveyors()) {
            if (!feeder.getTargetLocationId().equals(otherFeeder.getTargetLocationId()) || !otherFeeder.isActive()) continue;
            for (String otherId : liveConveyorRepository.getItemsOrderedByDistance(otherFeeder.getId())) {
                if (otherId.equals(current.getId())) continue;
                var other = liveItemRepository.getItemState(otherId);
                if (other == null || other.isMovementPaused() || other.getMovementCheckTimestamp() == null) continue;
                if (!nextId.equals(calculateNextConveyorIgnoringAvailability(otherId, otherFeeder.getTargetLocationId()))) continue;
                if (nextId.equals(other.getPlannedPositionId()) && other.getPlannedTransitionTimestamp() != null) return true;
                if (projectedMeters(other, otherFeeder, at) + 1e-9
                        < mergeHoldingPosition(otherFeeder, otherId, nextId)) continue;
                Instant otherArrival = other.isFlowPaused() ? other.getEntryTime()
                        : other.getMovementCheckTimestamp().isBefore(at) ? other.getMovementCheckTimestamp() : at;
                if (otherArrival != null && (arrival == null || otherArrival.isBefore(arrival)
                        || otherArrival.equals(arrival) && otherId.compareTo(current.getId()) < 0)) return true;
            }
        }
        return false;
    }

    private double projectedMeters(com.flunav.backend.models.RedisLiveItem state, Conveyor conveyor, Instant at) {
        double distance = conveyor.getLength() * state.getAccumulatedDistance() / 100.0;
        if (!state.isMovementPaused() && !state.isFlowPaused()
                && !liveConveyorRepository.isFlowStopped(conveyor.getId()) && conveyor.isActive()
                && state.getEntryTime() != null && conveyor.getSpeed() != null) {
            distance += Math.max(0L, at.toEpochMilli() - state.getEntryTime().toEpochMilli())
                    / 1000.0 * conveyor.getSpeed();
        }
        return Math.min(conveyor.getLength(), distance);
    }

    private double itemLengthMeters(String itemId) {
        Item item = itemService.getItemById(itemId);
        if (item != null && item.getProperties() != null) {
            for (String key : List.of("lengthCm", "length")) {
                Object value = item.getProperties().get(key);
                try {
                    double cm = Double.parseDouble(String.valueOf(value));
                    if (Double.isFinite(cm) && cm > 0) return cm / 100.0;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return averageItemLengthMeters;
    }

    private boolean hasEntrySpace(String conveyorId, String itemId, Instant at) {
        Conveyor target = topologyProvider.getConveyorById(conveyorId);
        if (target == null || !target.isActive() || target.getSpeed() == null || target.getSpeed() <= 0
                || isNextSegmentBlocked(target, itemId)) return false;
        double requiredBase = itemLengthMeters(itemId) / 2.0
                + Math.max(0.0, Objects.requireNonNullElse(target.getMinDistance(), 0.0));
        for (String otherId : liveConveyorRepository.getItemsOrderedByDistance(conveyorId)) {
            var other = liveItemRepository.getItemState(otherId);
            if (other == null) continue;
            double required = requiredBase + itemLengthMeters(otherId) / 2.0;
            if (projectedMeters(other, target, at) + 1e-9 < required) return false;
        }
        return true;
    }

    private Instant predictedClearance(String conveyorId, String itemId, Instant at) {
        Conveyor target = topologyProvider.getConveyorById(conveyorId);
        Instant retry = at.plusMillis(1);
        if (target == null || !target.isActive() || target.getSpeed() == null || target.getSpeed() <= 0)
            return at.plusMillis(1000);
        for (Conveyor feeder : topologyProvider.getAllConveyors()) {
            if (!target.getSourceLocationId().equals(feeder.getTargetLocationId())) continue;
            for (String otherId : liveConveyorRepository.getItemsOrderedByDistance(feeder.getId())) {
                if (otherId.equals(itemId)) continue;
                var other = liveItemRepository.getItemState(otherId);
                if (other != null && conveyorId.equals(other.getPlannedPositionId())
                        && other.getPlannedTransitionTimestamp() != null
                        && !other.getPlannedTransitionTimestamp().isBefore(at)) {
                    Instant clearance = other.getPlannedTransitionTimestamp().plusMillis(1);
                    if (clearance.isAfter(retry)) retry = clearance;
                }
            }
        }
        for (String otherId : liveConveyorRepository.getItemsOrderedByDistance(conveyorId)) {
            var other = liveItemRepository.getItemState(otherId);
            if (other == null || other.isFlowPaused() || other.isMovementPaused()) return at.plusMillis(1000);
            double required = (itemLengthMeters(itemId) + itemLengthMeters(otherId)) / 2.0
                    + Math.max(0.0, Objects.requireNonNullElse(target.getMinDistance(), 0.0));
            double remaining = required - projectedMeters(other, target, at);
            if (remaining > 0) {
                Instant clearance = at.plusMillis((long) Math.ceil(remaining / target.getSpeed() * 1000.0));
                if (clearance.isAfter(retry)) retry = clearance;
            }
        }
        return retry;
    }

    private void pauseForFlow(String itemId, Conveyor feeder, Instant timestamp, double position,
            boolean shouldBroadcast) {
        if (feeder.getType() == ConveyorType.BELT && !liveConveyorRepository.isFlowStopped(feeder.getId())) {
            checkpointItems(feeder.getId(), feeder.getSpeed(), timestamp);
            liveItemRepository.checkpointPhysics(itemId, timestamp, position / feeder.getLength() * 100.0);
            for (String occupant : liveConveyorRepository.getItemsOrderedByDistance(feeder.getId())) {
                cancelScheduledEvent(occupant);
                liveItemRepository.setFlowPaused(occupant, true);
                if (shouldBroadcast) {
                    var state = liveItemRepository.getItemState(occupant);
                    webSocketService.broadcastItemUpdated(
                            new UpdateModel(occupant, Map.of("flowPaused", true)), timestamp);
                    webSocketService.broadcastPositionUpdate(occupant, feeder.getId(), timestamp,
                            PositionType.CONVEYOR, state.getAccumulatedDistance());
                }
            }
            liveConveyorRepository.setFlowStopped(feeder.getId(), true);
            if (shouldBroadcast) webSocketService.broadcastConnectionUpdated(
                    new UpdateModel(feeder.getId(), Map.of("flowStopped", true)), timestamp);
        } else if (feeder.getType() != ConveyorType.BELT) {
            double progress = feeder.getLength() > 0 ? Math.max(0.0, position) / feeder.getLength() * 100.0 : 0.0;
            var previous = liveItemRepository.getItemState(itemId);
            if (previous == null || !previous.isFlowPaused()
                    || Math.abs(previous.getAccumulatedDistance() - progress) > 1e-9) {
                liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
            }
            liveItemRepository.setFlowPaused(itemId, true);
            liveItemRepository.clearPlannedTransition(itemId);
            if (shouldBroadcast) webSocketService.broadcastPositionUpdate(itemId, feeder.getId(), timestamp,
                    PositionType.CONVEYOR, progress);
            for (String followerId : liveConveyorRepository.getItemsOrderedByDistance(feeder.getId())) {
                if (followerId.equals(itemId)) continue;
                var follower = liveItemRepository.getItemState(followerId);
                if (follower == null || follower.isMovementPaused() || follower.isFlowPaused()
                        || projectedMeters(follower, feeder, timestamp) >= position) continue;
                checkpointItem(followerId, timestamp, shouldBroadcast);
                var checkpoint = liveItemRepository.getItemState(followerId);
                handleItemEntryToConveyor(followerId, feeder.getId(), timestamp,
                        checkpoint.getAccumulatedDistance(), null, shouldBroadcast);
            }
        }
        if (shouldBroadcast) webSocketService.broadcastItemUpdated(
                new UpdateModel(itemId, Map.of("flowPaused", true)), timestamp);
    }

    private void resumeRollerFollowers(Conveyor conveyor, Instant timestamp, boolean shouldBroadcast) {
        for (String occupant : liveConveyorRepository.getItemsOrderedByDistance(conveyor.getId())) {
            var state = liveItemRepository.getItemState(occupant);
            if (state == null || !state.isFlowPaused() || state.isMovementPaused()) continue;
            liveItemRepository.setFlowPaused(occupant, false);
            liveItemRepository.checkpointPhysics(occupant, timestamp, state.getAccumulatedDistance());
            if (shouldBroadcast) {
                webSocketService.broadcastItemUpdated(new UpdateModel(occupant, Map.of("flowPaused", false)), timestamp);
                webSocketService.broadcastPositionUpdate(occupant, conveyor.getId(), timestamp,
                        PositionType.CONVEYOR, state.getAccumulatedDistance());
            }
            handleItemEntryToConveyor(occupant, conveyor.getId(), timestamp,
                    state.getAccumulatedDistance(), null, shouldBroadcast);
        }
    }

    private void wakeUpWaitingFeeders(String downstreamId, Instant timestamp) {
        Conveyor downstream = topologyProvider.getConveyorById(downstreamId);
        if (downstream == null) return;
        for (Conveyor feeder : topologyProvider.getAllConveyors()) {
            if (!feeder.getTargetLocationId().equals(downstream.getSourceLocationId())) continue;
            for (String itemId : liveConveyorRepository.getItemsOrderedByDistance(feeder.getId())) {
                var state = liveItemRepository.getItemState(itemId);
                if (state != null && state.isFlowPaused() && state.getMovementCheckTimestamp() != null) {
                    scheduleMovementCheck(itemId, feeder.getId(), timestamp.plusMillis(1));
                }
            }
        }
    }

    /** Replaces a stale route or position plan from its current physical checkpoint. */
    public void refreshItemMovement(String itemId, Instant timestamp, boolean shouldBroadcast) {
        var state = liveItemRepository.getItemState(itemId);
        if (state == null || state.getType() != PositionType.CONVEYOR || state.getPositionId() == null) return;
        checkpointItem(itemId, timestamp, shouldBroadcast);
        cancelScheduledEvent(itemId);
        state = liveItemRepository.getItemState(itemId);
        if (state.isMovementPaused()) return;
        if (state.isFlowPaused()) {
            scheduleMovementCheck(itemId, state.getPositionId(), timestamp.plusMillis(1));
        } else {
            handleItemEntryToConveyor(itemId, state.getPositionId(), timestamp,
                    state.getAccumulatedDistance(), null, shouldBroadcast);
        }
    }

    /** A confirmed departure or manual move may release waiting feeder admissions. */
    public void onConveyorOccupancyChanged(String conveyorId, Instant timestamp, boolean shouldBroadcast) {
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null) return;
        if (conveyor.getType() == ConveyorType.BELT && liveConveyorRepository.isFlowStopped(conveyorId)) {
            boolean hasPendingLead = liveConveyorRepository.getItemsOrderedByDistance(conveyorId).stream()
                    .map(liveItemRepository::getItemState)
                    .anyMatch(item -> item != null && item.getMovementCheckTimestamp() != null);
            if (!hasPendingLead) {
                liveConveyorRepository.setFlowStopped(conveyorId, false);
                if (shouldBroadcast) webSocketService.broadcastConnectionUpdated(
                        new UpdateModel(conveyorId, Map.of("flowStopped", false)), timestamp);
                resumeConveyorFromCheckpoints(conveyorId, timestamp, shouldBroadcast);
            }
        }
        if (conveyor.getType() == ConveyorType.ROLLER) {
            resumeRollerFollowers(conveyor, timestamp, shouldBroadcast);
        }
        wakeUpWaitingFeeders(conveyorId, timestamp);
    }

    public boolean isNextSegmentBlocked(Conveyor nextConv) {
        return isNextSegmentBlocked(nextConv, null);
    }

    /**
     * Checks whether the next conveyor can accept the current item.
     * Chute capacity is evaluated with projected assignments as well as physical
     * occupants so future arrivals do not overbook the same destination.
     */
    private boolean isNextSegmentBlocked(Conveyor nextConv, String itemId) {
        if (nextConv == null)
            return true;
        if (!nextConv.isActive())
            return true;

        var targetLocation = topologyProvider.getLocationById(nextConv.getTargetLocationId());
        if (targetLocation != null && targetLocation.getType() == LocationType.CHUTE) {
            Integer capacity = targetLocation.getCapacity();
            long projectedOccupancy = projectedChuteOccupancy(targetLocation.getId(), itemId);
            return capacity != null && capacity > 0 && projectedOccupancy >= capacity;
        }
        return false;
    }

    /**
     * Counts current and already assigned chute demand for capacity checks.
     * The moving item can be excluded to avoid counting its current assignment
     * twice while rerouting or revalidating a path.
     */
    private long projectedChuteOccupancy(String chuteId, String itemId) {
        long countStarted = SimulationRunTiming.tick();
        long occupancy = liveLocationRepository.getItemCount(chuteId)
                + liveItemRepository.countItemsAssignedToExit(chuteId, itemId);
        SimulationRunTiming.record("routing.projected-chute-occupancy", countStarted);
        return occupancy;
    }

    /**
     * Rechecks upstream conveyors when a location may have become available.
     * This wakes accumulated items after chute emptying or topology changes without
     * waiting for their old scheduled event.
     */
    public void wakeUpPrecedingConveyors(String locationId) {
        if (!manageLogic)
            return;
        topologyProvider.getAllConveyors().stream()
                .filter(c -> c.getTargetLocationId().equals(locationId))
                .forEach(c -> {
                    recalculateConveyorAccumulation(c.getId());
                    for (String itemId : liveConveyorRepository.getItemsOrderedByDistance(c.getId())) {
                        var state = liveItemRepository.getItemState(itemId);
                        if (state != null && state.isFlowPaused()
                                && state.getMovementCheckTimestamp() != null) {
                            scheduleMovementCheck(itemId, c.getId(), timeService.now().plusMillis(1));
                        }
                    }
                });
    }

    /**
     * Recomputes stop or arrival events for every item on a conveyor.
     * Items are processed in Redis conveyor order so accumulation calculations see
     * the leading items before following items.
     */
    public void recalculateConveyorAccumulation(String conveyorId) {
        if (!manageLogic)
            return;
        Set<String> items = liveConveyorRepository.getItemsOrderedByDistance(conveyorId);
        Instant now = timeService.now();
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null)
            return;
        if (conveyor.getType() == ConveyorType.STAGING) {
            return;
        }
        for (String itemId : items) {
            var state = liveItemRepository.getItemState(itemId);
            if (state == null || state.isMovementPaused() || state.isFlowPaused()
                    || liveConveyorRepository.isFlowStopped(conveyorId))
                continue;
            double progress = state.getAccumulatedDistance();
            if (conveyor.isActive() && conveyor.getSpeed() > 0 && state.getEntryTime() != null) {
                long elapsedMillis = Math.max(0L, now.toEpochMilli() - state.getEntryTime().toEpochMilli());
                double progressDelta = conveyor.getLength() > 0
                        ? elapsedMillis / 1000.0 * conveyor.getSpeed() / conveyor.getLength() * 100.0
                        : 0.0;
                progress = Math.min(100.0, Math.max(0.0, progress + progressDelta));
                liveItemRepository.checkpointPhysics(itemId, now, progress);
            }
            handleItemEntryToConveyor(itemId, conveyorId, now, progress, null);
        }
    }

    /**
     * Restarts items from their frozen checkpoints without treating the stopped
     * interval as conveyor travel.
     */
    public void resumeConveyorFromCheckpoints(String conveyorId, Instant timestamp, boolean shouldBroadcast) {
        if (!manageLogic)
            return;
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null || !conveyor.isActive() || conveyor.getSpeed() <= 0
                || liveConveyorRepository.isFlowStopped(conveyorId)) {
            return;
        }

        for (String itemId : liveConveyorRepository.getItemsOrderedByDistance(conveyorId)) {
            var state = liveItemRepository.getItemState(itemId);
            if (state == null || state.isMovementPaused() || state.getType() != PositionType.CONVEYOR
                    || !conveyorId.equals(state.getPositionId())) {
                continue;
            }
            double progress = Math.min(100.0, Math.max(0.0, state.getAccumulatedDistance()));
            liveItemRepository.setFlowPaused(itemId, false);
            liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
            if (shouldBroadcast) {
                webSocketService.broadcastItemUpdated(
                        new UpdateModel(itemId, Map.of("flowPaused", false)), timestamp);
                webSocketService.broadcastPositionUpdate(itemId, conveyorId, timestamp,
                        PositionType.CONVEYOR, progress);
            }
            if (conveyor.getType() != ConveyorType.STAGING) {
                handleItemEntryToConveyor(itemId, conveyorId, timestamp, progress, null);
            }
        }
    }

    /**
     * Schedules the currently staged FIFO batch and records each pending transition
     * before handing it to the live or simulation scheduler.
     */
    public Map<String, Object> releaseStagingConveyor(Conveyor conveyor, Instant timestamp,
            boolean shouldBroadcast) {
        List<String> ordered = new java.util.ArrayList<>(
                liveConveyorRepository.getItemsOrderedByDistance(conveyor.getId()));
        Map<String, Double> positions = stagingPositions(conveyor, ordered, timestamp);
        int scheduled = 0;
        int alreadyScheduled = 0;
        Instant precedingDeparture = null;
        double speed = conveyor.getSpeed();
        double spacing = Math.max(0.0, Objects.requireNonNullElse(conveyor.getMinDistance(), 0.1));

        for (String itemId : ordered) {
            var state = liveItemRepository.getItemState(itemId);
            if (state == null || state.isMovementPaused() || state.getType() != PositionType.CONVEYOR
                    || !conveyor.getId().equals(state.getPositionId())) {
                continue;
            }
            if (state.getPlannedTransitionTimestamp() != null && state.getPlannedPositionId() != null) {
                alreadyScheduled++;
                if (precedingDeparture == null
                        || state.getPlannedTransitionTimestamp().isAfter(precedingDeparture)) {
                    precedingDeparture = state.getPlannedTransitionTimestamp();
                }
                continue;
            }

            double distance = positions.getOrDefault(itemId, 0.0);
            double progress = conveyor.getLength() > 0 ? distance / conveyor.getLength() * 100.0 : 0.0;
            liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
            if (shouldBroadcast) {
                webSocketService.broadcastPositionUpdate(itemId, conveyor.getId(), timestamp,
                        PositionType.CONVEYOR, progress);
            }

            long physicalTravelMillis = (long) Math.ceil(
                    Math.max(0.0, conveyor.getLength() - distance) / speed * 1000.0);
            Instant departure = timestamp.plusMillis(physicalTravelMillis);
            if (precedingDeparture != null) {
                long spacingMillis = (long) Math.ceil(spacing / speed * 1000.0);
                Instant spacedDeparture = precedingDeparture.plusMillis(spacingMillis);
                if (departure.isBefore(spacedDeparture)) {
                    departure = spacedDeparture;
                }
            }

            String nextConveyorId = calculateNextConveyorIgnoringAvailability(
                    itemId, conveyor.getTargetLocationId());
            String plannedPositionId = nextConveyorId != null
                    ? nextConveyorId
                    : conveyor.getTargetLocationId();
            PositionType plannedType = nextConveyorId != null ? PositionType.CONVEYOR : PositionType.LOCATION;
            ItemPositionChangedEvent transition = new ItemPositionChangedEvent(
                    itemId, plannedPositionId, nextConveyorId != null ? 0.0 : 100.0, departure);
            liveItemRepository.setPlannedTransition(itemId, plannedPositionId, plannedType, departure);
            scheduleEvent(transition);
            precedingDeparture = departure;
            scheduled++;
        }
        return Map.of(
                "status", "PROCESSED_SUCCESSFULLY",
                "scheduledCount", scheduled,
                "alreadyScheduledCount", alreadyScheduled);
    }

    /** Checkpoints and optionally cancels every staged item at its clamped FIFO position. */
    public void checkpointStagingItems(Conveyor conveyor, Instant timestamp, boolean cancelPlans,
            boolean shouldBroadcast) {
        List<String> ordered = new java.util.ArrayList<>(
                liveConveyorRepository.getItemsOrderedByDistance(conveyor.getId()));
        Map<String, Double> positions = stagingPositions(conveyor, ordered, timestamp);
        for (String itemId : ordered) {
            Double distance = positions.get(itemId);
            if (distance == null) {
                continue;
            }
            double progress = conveyor.getLength() > 0 ? distance / conveyor.getLength() * 100.0 : 0.0;
            liveItemRepository.checkpointPhysics(itemId, timestamp, progress);
            if (cancelPlans) {
                cancelScheduledEvent(itemId);
                liveItemRepository.clearPlannedTransition(itemId);
            }
            if (shouldBroadcast) {
                webSocketService.broadcastPositionUpdate(itemId, conveyor.getId(), timestamp,
                        PositionType.CONVEYOR, progress);
            }
        }
    }

    private Map<String, Double> stagingPositions(Conveyor conveyor, List<String> ordered, Instant timestamp) {
        Map<String, Double> positions = new java.util.LinkedHashMap<>();
        double length = Math.max(0.0, Objects.requireNonNullElse(conveyor.getLength(), 0.0));
        double speed = Math.max(0.0, Objects.requireNonNullElse(conveyor.getSpeed(), 0.0));
        double spacing = Math.max(0.0, Objects.requireNonNullElse(conveyor.getMinDistance(), 0.1));
        for (int index = 0; index < ordered.size(); index++) {
            String itemId = ordered.get(index);
            var state = liveItemRepository.getItemState(itemId);
            if (state == null || state.getEntryTime() == null) {
                continue;
            }
            long elapsedMillis = state.isMovementPaused()
                    ? 0L
                    : Math.max(0L, timestamp.toEpochMilli() - state.getEntryTime().toEpochMilli());
            double storedDistance = length * Math.min(100.0, Math.max(0.0, state.getAccumulatedDistance())) / 100.0;
            double naturalDistance = storedDistance
                    + elapsedMillis / 1000.0 * speed;
            double slot = Math.max(0.0, length - index * spacing);
            positions.put(itemId, Math.min(naturalDistance, slot));
        }
        return positions;
    }

    /** Selects release routing without rejecting inactive or capacity-blocked downstream conveyors. */
    private String calculateNextConveyorIgnoringAvailability(String itemId, String currentLocationId) {
        var item = itemService.getItemById(itemId);
        List<Conveyor> outgoing = topologyProvider.getOutgoingConveyors(currentLocationId);
        if (outgoing.isEmpty()) {
            return null;
        }
        if (item != null && item.getPath() != null) {
            int currentIndex = item.getPath().indexOf(currentLocationId);
            if (currentIndex >= 0 && currentIndex < item.getPath().size() - 1) {
                String nextLocation = item.getPath().get(currentIndex + 1);
                String matched = outgoing.stream()
                        .filter(candidate -> nextLocation.equals(candidate.getTargetLocationId()))
                        .map(Conveyor::getId)
                        .findFirst()
                        .orElse(null);
                if (matched != null) {
                    return matched;
                }
            }
        }
        return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                .orElse(outgoing.get(0).getId());
    }

    /**
     * Applies the domain effect of an item entering a location.
     * Chutes retain items as occupancy, while other locations immediately route
     * onward so decision-point logic remains event-driven.
     */
    public void processLocationEntry(String itemId, String locationId, Instant timestamp) {
        processLocationEntry(itemId, locationId, timestamp, true);
    }

    /**
     * Applies location-entry side effects and immediately advances non-chute items.
     * Chute entries become occupancy, while other locations are treated as transient
     * decision points or pass-through nodes in managed movement mode.
     */
    public void processLocationEntry(String itemId, String locationId, Instant timestamp, boolean publishAssignments) {
        var location = topologyProvider.getLocationById(locationId);
        if (location == null)
            return;

        if (location.getType() == LocationType.CHUTE) {
            itemService.updateItemRoutingStatus(itemId, RoutingStatus.COMPLETED, timestamp);
            liveLocationRepository.addItemToLocation(locationId, itemId);
            if (multiSimulationMetricsService.isCollectingCurrentSimulation()) {
                scheduleEvent(new ItemExitedEvent(itemId, locationId, timestamp));
            }
            if (publishAssignments) {
                webSocketService.broadcastItemUpdated(
                        new UpdateModel(itemId, Map.of(
                                "routingStatus", RoutingStatus.COMPLETED,
                                "routingStatusUpdatedAt", timestamp)),
                        timestamp);
            }
            return;
        }

        if (location.getType() == LocationType.TIMED_NODE) {
            liveLocationRepository.addItemToLocation(locationId, itemId);
            if (!manageLogic) {
                return;
            }

            long processingDelayMillis = processingDelayMillis(location);
            if (processingDelayMillis > 0L) {
                scheduleEvent(new ItemProcessingCompletedEvent(itemId, locationId,
                        timestamp.plusMillis(processingDelayMillis)));
                return;
            }

            releaseTimedNodeItem(itemId, locationId, timestamp, publishAssignments, false);
            return;
        }

        if (!manageLogic) {
            return;
        }

        releaseFromPassThroughLocation(itemId, locationId, timestamp, publishAssignments, false);
    }

    /**
     * Releases an item from a timed node after its processing delay has elapsed.
     * The current hot-state check prevents stale scheduled completions from moving
     * an item that was manually repositioned before the timer fired.
     */
    public void processTimedNodeCompletion(String itemId, String locationId, Instant timestamp,
            boolean publishAssignments) {
        var state = liveItemRepository.getItemState(itemId);
        if (state == null
                || !locationId.equals(state.getPositionId())
                || state.getType() != PositionType.LOCATION) {
            return;
        }

        var location = topologyProvider.getLocationById(locationId);
        if (location == null || location.getType() != LocationType.TIMED_NODE) {
            return;
        }

        releaseTimedNodeItem(itemId, locationId, timestamp, publishAssignments, true);
    }

    /**
     * Removes timed-node occupancy before handing the item to normal routing.
     * Occupancy belongs to the delay interval only; after release, conveyor and
     * location repositories resume their usual ownership.
     */
    private void releaseTimedNodeItem(String itemId, String locationId, Instant timestamp, boolean publishAssignments,
            boolean broadcastReleasedPosition) {
        liveLocationRepository.removeItemFromLocation(locationId, itemId);
        releaseFromPassThroughLocation(itemId, locationId, timestamp, publishAssignments, broadcastReleasedPosition);
    }

    /**
     * Advances a non-terminal location occupant onto its selected next conveyor.
     * This is shared by normal pass-through locations and timed-node releases.
     */
    private void releaseFromPassThroughLocation(String itemId, String locationId, Instant timestamp,
            boolean publishAssignments, boolean broadcastReleasedPosition) {
        long stepStarted = SimulationRunTiming.tick();
        String nextConveyorId = calculateNextConveyor(itemId, locationId, null, publishAssignments);
        SimulationRunTiming.record("entry.choose-conveyor", stepStarted);
        if (nextConveyorId != null) {
            stepStarted = SimulationRunTiming.tick();
            itemService.updateItemPosition(itemId, nextConveyorId, PositionType.CONVEYOR, timestamp, 0.0, null);
            SimulationRunTiming.record("entry.write-position", stepStarted);
            stepStarted = SimulationRunTiming.tick();
            liveConveyorRepository.addItemToConveyor(nextConveyorId, itemId, timestamp);
            SimulationRunTiming.record("entry.add-conveyor-occupancy", stepStarted);
            if (publishAssignments && broadcastReleasedPosition) {
                webSocketService.broadcastPositionUpdate(itemId, nextConveyorId, timestamp,
                        PositionType.CONVEYOR, 0.0);
            }
            stepStarted = SimulationRunTiming.tick();
            handleItemEntryToConveyor(itemId, nextConveyorId, timestamp, 0.0, locationId, publishAssignments);
            SimulationRunTiming.record("entry.schedule-conveyor-motion", stepStarted);
        }
    }

    private long processingDelayMillis(com.flunav.backend.domain.Location location) {
        Long delay = location.getTimeToProcessMs();
        return delay != null && delay > 0L ? delay : 0L;
    }

    /**
     * Recomputes routing at a decision point under the routing lock.
     * The lock serializes capacity-sensitive selection so concurrent arrivals do
     * not claim the same chute slot from live or simulation state.
     */
    private Item recalculateDecisionPointRoute(Item item, String locationId, boolean publishAssignments,
            Instant timestamp) {
        String oldSelectedExitId = item.getSelectedExitId();
        RoutingStatus oldRoutingStatus = item.getRoutingStatus();
        List<String> oldPath = item.getPath();
        var decision = routingCoordinator.withRoutingLock(() -> {
            var selected = routingDecisionService.selectRoute(item, locationId, PositionType.LOCATION, timestamp);
            itemService.updateItemRouting(item.getId(), item.getDestinations(), selected.selectedExitId(),
                    selected.routingStatus(), timestamp, selected.path());
            return selected;
        });
        item.setSelectedExitId(decision.selectedExitId());
        item.setRoutingStatus(decision.routingStatus());
        item.setRoutingStatusUpdatedAt(timestamp);
        item.setPath(decision.path());
        boolean changed = !java.util.Objects.equals(oldSelectedExitId, decision.selectedExitId())
                || oldRoutingStatus != decision.routingStatus()
                || !java.util.Objects.equals(oldPath, decision.path());
        if (changed) {
            operationalAnalyticsService.recordRecirculation(
                    item.getId(), oldPath, decision.path(), timestamp);
            pathAssignmentPublisher.publishIfAssigned(
                    item.getId(),
                    decision.selectedExitId(),
                    decision.routingStatus(),
                    decision.path(),
                    timestamp,
                    publishAssignments);
        }
        if (publishAssignments) {
            DestinationMappingService.RushPriority rush = routingDecisionService.effectivePriority(item, timestamp);
            Map<String, Object> updates = new HashMap<>();
            updates.put("selectedExitId", decision.selectedExitId());
            updates.put("routingStatus", decision.routingStatus());
            updates.put("routingStatusUpdatedAt", timestamp);
            updates.put("path", decision.path());
            updates.put("effectivePriority", rush.effectivePriority());
            updates.put("rushActive", rush.rushActive());
            webSocketService.broadcastItemUpdated(new UpdateModel(item.getId(), updates), timestamp);
        }
        return item;
    }

    /**
     * Sends projected movement to the correct scheduler for the current context.
     * Simulation events stay in SimulationState so replay can project them without
     * persisting artificial future history to ClickHouse.
     */
    public void scheduleEvent(DomainEvent event) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null)
            simulationService.addInternalEvent(event);
        else
            liveSystemScheduler.scheduleInternalEvent(event);
    }

    /**
     * Removes the pending movement event for the current context.
     * Live mode cancels the scheduler task, while simulation mode removes the event
     * from SimulationState so future replay projection stays isolated. Cancellation
     * also releases any reserved transition so another feeder can claim the gap.
     */
    public void cancelScheduledEvent(String itemId) {
        liveItemRepository.setMovementCheck(itemId, null);
        liveItemRepository.clearPlannedTransition(itemId);
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null)
            simulationService.cancelInternalEvent(itemId);
        else
            liveSystemScheduler.cancelInternalEvent(itemId);
    }

    /**
     * Reads the currently scheduled movement event from live or simulation state.
     * Recovery and replay code use this to avoid scheduling duplicate arrivals for
     * the same item.
     */
    public DomainEvent getScheduledEvent(String itemId) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return simulationService.getScheduledEvent(itemId);
        } else {
            return liveSystemScheduler.getScheduledEvent(itemId);
        }
    }

    /**
     * Resolves the conveyor an item should take from its current location.
     * Assigned paths are honored first, but blocked assigned exits fall back to the
     * main path so items can recirculate instead of jamming decision points.
     */
    public String calculateNextConveyor(String itemId, String currentLocationId, String currentConveyorId) {
        return calculateNextConveyor(itemId, currentLocationId, currentConveyorId, true);
    }

    /**
     * Chooses the next active conveyor from the current location.
     * Assigned paths are followed when possible; blocked assigned exits recirculate
     * onto the main path so capacity-constrained items keep moving.
     */
    public String calculateNextConveyor(String itemId, String currentLocationId, String currentConveyorId,
            boolean publishAssignments) {
        return calculateNextConveyor(itemId, currentLocationId, currentConveyorId,
                publishAssignments, timeService.now());
    }

    private String calculateNextConveyor(String itemId, String currentLocationId, String currentConveyorId,
            boolean publishAssignments, Instant timestamp) {
        var item = itemService.getItemById(itemId);
        if (item == null)
            return null;
        var currentLocation = topologyProvider.getLocationById(currentLocationId);
        if (manageLogic && currentLocation != null && currentLocation.getType() == LocationType.DECISION_POINT) {
            item = recalculateDecisionPointRoute(item, currentLocationId, publishAssignments, timestamp);
        }
        List<Conveyor> outgoing = topologyProvider.getOutgoingConveyors(currentLocationId).stream()
                .filter(Conveyor::isActive).toList();
        if (outgoing.isEmpty())
            return null;

        String targetConveyorId = null;
        if (item.getPath() != null && !item.getPath().isEmpty()) {
            int currentIndex = item.getPath().indexOf(currentLocationId);
            if (currentIndex >= 0 && currentIndex < item.getPath().size() - 1) {
                String nextVertexId = item.getPath().get(currentIndex + 1);
                targetConveyorId = outgoing.stream().filter(c -> c.getTargetLocationId().equals(nextVertexId))
                        .map(Conveyor::getId).findFirst().orElse(null);
            }
        }

        if (targetConveyorId != null) {
            Conveyor target = topologyProvider.getConveyorById(targetConveyorId);
            if (item.getRoutingStatus() == RoutingStatus.ASSIGNED && target != null && isNextSegmentBlocked(target, itemId)) {
                return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                        .orElse(targetConveyorId);
            }
            return targetConveyorId;
        }
        return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                .orElse(outgoing.get(0).getId());
    }

    /**
     * Looks for a usable main-path alternative when a selected exit path is blocked.
     * This keeps urgent or capacity-constrained items moving around the loop until
     * a valid exit can be selected again.
     */
    private String findRecirculationPath(String currentLocationId, String blockedConveyorId, String itemId) {
        return topologyProvider.getOutgoingConveyors(currentLocationId).stream()
                .filter(Conveyor::isActive)
                .filter(c -> c.isMainPath())
                .filter(c -> !c.getId().equals(blockedConveyorId))
                .filter(c -> !isNextSegmentBlocked(c, itemId))
                .map(Conveyor::getId)
                .findFirst()
                .orElse(null);
    }
}
