package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.domain.Item;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.simulation.SimulationState;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.context.DatabaseContextHolder;
import flunav.events.DomainEvent;
import flunav.events.ItemProcessingCompletedEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final boolean manageLogic;

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
            @Value("${simulation.manage-logic:true}") boolean manageLogic) {
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
        this.manageLogic = manageLogic;
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
     * changes are applied. This preserves physical distance already traveled so
     * rescheduled movement starts from the correct conveyor offset.
     */
    public void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        var allItems = liveConveyorRepository.getItemsOrderedByDistance(edgeId);
        Instant nowInstant = timestamp;

        for (var itemId : allItems) {
            var itemData = liveItemRepository.getItemState(itemId);
            var lastUpdateTime = itemData.getEntryTime();
            Double storedDistance = itemData.getAccumulatedDistance();

            if (lastUpdateTime != null) {
                long timeElapsed = nowInstant.toEpochMilli() - lastUpdateTime.toEpochMilli();
                double distanceTraveledSinceLastUpdate = (timeElapsed / 1000.0) * oldSpeed;
                double totalDistance = storedDistance + distanceTraveledSinceLastUpdate;
                liveItemRepository.checkpointPhysics(itemId, nowInstant, totalDistance);
                handleItemEntryToConveyor(itemId, edgeId, nowInstant, (totalDistance / oldSpeed) * 100, null);
            }
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
        Conveyor conveyor = topologyProvider.getConveyorById(conveyorId);
        if (conveyor == null)
            return;

        double speed = conveyor.getSpeed();
        double length = conveyor.getLength();
        Double minDistance = conveyor.getMinDistance();

        // 1. Handle Stopped Conveyor
        if (speed <= 0) {
            cancelScheduledEvent(itemId);
            Double currentTail = liveConveyorRepository.getTailPosition(conveyorId);
            double itemDistance = length * (progress / 100.0);
            if (currentTail == null || itemDistance < currentTail) {
                liveConveyorRepository.updateTailPosition(conveyorId, itemDistance);
            }
            return;
        }

        double remainingDistance = length * (1.0 - progress / 100.0);
        long travelTimeMillis = (long) ((remainingDistance / speed) * 1000);
        Instant arrivalAtEnd = timestamp.plusMillis(travelTimeMillis);

        var targetLocation = topologyProvider.getLocationById(conveyor.getTargetLocationId());
        if (targetLocation != null && targetLocation.getType() == LocationType.TIMED_NODE) {
            scheduleEvent(new ItemPositionChangedEvent(itemId, targetLocation.getId(), 100.0, arrivalAtEnd));
            liveConveyorRepository.updateTailPosition(conveyorId, length);
            return;
        }

        String nextConveyorId = calculateNextConveyor(itemId, conveyor.getTargetLocationId(), conveyorId,
                publishAssignments);

        if (nextConveyorId != null) {
            Conveyor nextConv = topologyProvider.getConveyorById(nextConveyorId);

            if (isNextSegmentBlocked(nextConv, itemId)) {
                String alternativeId = findRecirculationPath(conveyor.getTargetLocationId(), nextConveyorId, itemId);
                if (alternativeId != null) {
                    nextConveyorId = alternativeId;
                    nextConv = topologyProvider.getConveyorById(nextConveyorId);
                }
            }

            if (isNextSegmentBlocked(nextConv, itemId)) {
                double effectiveMinDist = (minDistance != null) ? minDistance : 0.0;
                Double nextTail = liveConveyorRepository.getTailPosition(nextConveyorId);
                if (nextTail == null)
                    nextTail = 0.0;

                double stopAt = length - (nextTail + effectiveMinDist);
                if (stopAt < 0)
                    stopAt = 0;

                double distanceToStop = stopAt - (length * (progress / 100.0));

                if (distanceToStop > 0) {
                    long timeToStop = (long) ((distanceToStop / speed) * 1000);
                    scheduleEvent(new ItemPositionChangedEvent(itemId, conveyorId, (stopAt / length) * 100,
                            timestamp.plusMillis(timeToStop)));
                    liveConveyorRepository.updateTailPosition(conveyorId, stopAt);
                } else {
                    cancelScheduledEvent(itemId);
                    double actualPos = length * (progress / 100.0);
                    if (actualPos > stopAt) {
                        itemService.updateItemPosition(itemId, conveyorId, PositionType.CONVEYOR, timestamp,
                                (stopAt / length) * 100, null);
                        liveConveyorRepository.updateTailPosition(conveyorId, stopAt);
                    } else {
                        liveConveyorRepository.updateTailPosition(conveyorId, actualPos);
                    }
                }
            } else {
                scheduleEvent(new ItemPositionChangedEvent(itemId, nextConveyorId, 0.0, arrivalAtEnd));
                liveConveyorRepository.updateTailPosition(conveyorId, length);
            }
        } else {
            if (targetLocation != null && targetLocation.getType() == LocationType.CHUTE) {
                Integer capacity = targetLocation.getCapacity();
                long projectedOccupancy = projectedChuteOccupancy(targetLocation.getId(), itemId);
                if (capacity != null && capacity > 0 && projectedOccupancy >= capacity) {
                    double effectiveMinDist = (minDistance != null) ? minDistance : 0.0;
                    double stopAt = length - effectiveMinDist;
                    long timeToStop = (long) (((stopAt - length * (progress / 100.0)) / speed) * 1000);
                    if (timeToStop > 0)
                        scheduleEvent(new ItemPositionChangedEvent(itemId, conveyorId, (stopAt / length) * 100,
                                timestamp.plusMillis(timeToStop)));
                    liveConveyorRepository.updateTailPosition(conveyorId, stopAt);
                } else {
                    scheduleEvent(new ItemPositionChangedEvent(itemId, targetLocation.getId(), 100.0, arrivalAtEnd));
                }
            }
        }
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
        return liveLocationRepository.getItemCount(chuteId)
                + liveItemRepository.countItemsAssignedToExit(chuteId, itemId);
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
                .forEach(c -> recalculateConveyorAccumulation(c.getId()));
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
        for (String itemId : items) {
            var state = liveItemRepository.getItemState(itemId);
            if (state == null)
                continue;
            handleItemEntryToConveyor(itemId, conveyorId, now,
                    (state.getAccumulatedDistance() / conveyor.getLength()) * 100, null);
        }
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
        String nextConveyorId = calculateNextConveyor(itemId, locationId, null, publishAssignments);
        if (nextConveyorId != null) {
            itemService.updateItemPosition(itemId, nextConveyorId, PositionType.CONVEYOR, timestamp, 0.0, null);
            liveConveyorRepository.addItemToConveyor(nextConveyorId, itemId, timestamp);
            if (publishAssignments && broadcastReleasedPosition) {
                webSocketService.broadcastPositionUpdate(itemId, nextConveyorId, timestamp,
                        PositionType.CONVEYOR, 0.0);
            }
            handleItemEntryToConveyor(itemId, nextConveyorId, timestamp, 0.0, locationId, publishAssignments);
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
    private Item recalculateDecisionPointRoute(Item item, String locationId, boolean publishAssignments) {
        Instant timestamp = timeService.now();
        String oldSelectedExitId = item.getSelectedExitId();
        RoutingStatus oldRoutingStatus = item.getRoutingStatus();
        List<String> oldPath = item.getPath();
        var decision = routingCoordinator.withRoutingLock(() -> {
            var selected = routingDecisionService.selectRoute(item, locationId, PositionType.LOCATION);
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
     * from SimulationState so future replay projection stays isolated.
     */
    public void cancelScheduledEvent(String itemId) {
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
        var item = itemService.getItemById(itemId);
        if (item == null)
            return null;
        var currentLocation = topologyProvider.getLocationById(currentLocationId);
        if (manageLogic && currentLocation != null && currentLocation.getType() == LocationType.DECISION_POINT) {
            item = recalculateDecisionPointRoute(item, currentLocationId, publishAssignments);
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
