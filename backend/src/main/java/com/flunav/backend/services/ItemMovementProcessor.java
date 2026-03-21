package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import com.flunav.backend.repositories.LiveLocationRepository;
import com.flunav.backend.context.DatabaseContextHolder;
import flunav.events.DomainEvent;
import flunav.events.ItemPositionChangedEvent;
import flunav.types.LocationType;
import flunav.types.PositionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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
    private final SimulationService simulationService;
    private final LiveSystemScheduler liveSystemScheduler;
    private final TimeService timeService;
    private final boolean manageLogic;

    public ItemMovementProcessor(
            AmqpTemplate amqpTemplate,
            @Value("${rabbitmq.routing-key.item-events}") String itemEventsRoutingKey,
            LiveConveyorRepository liveConveyorRepository,
            LiveItemRepository liveItemRepository,
            LiveLocationRepository liveLocationRepository,
            TopologyProvider topologyProvider,
            ItemService itemService,
            @Lazy SimulationService simulationService,
            LiveSystemScheduler liveSystemScheduler,
            TimeService timeService,
            @Value("${simulation.manage-logic:true}") boolean manageLogic) {
        this.amqpTemplate = amqpTemplate;
        this.itemEventsRoutingKey = itemEventsRoutingKey;
        this.liveConveyorRepository = liveConveyorRepository;
        this.liveItemRepository = liveItemRepository;
        this.liveLocationRepository = liveLocationRepository;
        this.topologyProvider = topologyProvider;
        this.itemService = itemService;
        this.simulationService = simulationService;
        this.liveSystemScheduler = liveSystemScheduler;
        this.timeService = timeService;
        this.manageLogic = manageLogic;
    }

    public void publishEvent(DomainEvent event) {
        try {
            amqpTemplate.convertAndSend(itemEventsRoutingKey, event);
        } catch (Exception e) {
            logger.error("Failed to publish event of type {}", event.getEventType(), e);
        }
    }

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

    public void handleItemEntryToConveyor(String itemId, String conveyorId, Instant timestamp, Double progress,
            String previousPosId) {
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

        String nextConveyorId = calculateNextConveyor(itemId, conveyor.getTargetLocationId(), conveyorId);

        if (nextConveyorId != null) {
            Conveyor nextConv = topologyProvider.getConveyorById(nextConveyorId);

            if (isNextSegmentBlocked(nextConv)) {
                String alternativeId = findRecirculationPath(conveyor.getTargetLocationId(), nextConveyorId);
                if (alternativeId != null) {
                    nextConveyorId = alternativeId;
                    nextConv = topologyProvider.getConveyorById(nextConveyorId);
                }
            }

            if (isNextSegmentBlocked(nextConv)) {
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
            var targetLocation = topologyProvider.getLocationById(conveyor.getTargetLocationId());
            if (targetLocation != null && targetLocation.getType() == LocationType.CHUTE) {
                Integer capacity = targetLocation.getCapacity();
                Long currentOccupancy = liveLocationRepository.getItemCount(targetLocation.getId());
                if (capacity != null && currentOccupancy >= capacity) {
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
        if (nextConv == null)
            return true;
        if (!nextConv.isActive())
            return true;

        var targetLocation = topologyProvider.getLocationById(nextConv.getTargetLocationId());
        if (targetLocation != null && targetLocation.getType() == LocationType.CHUTE) {
            Integer capacity = targetLocation.getCapacity();
            Long currentOccupancy = liveLocationRepository.getItemCount(targetLocation.getId());
            return (capacity != null && currentOccupancy >= capacity);
        }
        return false;
    }

    public void wakeUpPrecedingConveyors(String locationId) {
        if (!manageLogic)
            return;
        topologyProvider.getAllConveyors().stream()
                .filter(c -> c.getTargetLocationId().equals(locationId))
                .forEach(c -> recalculateConveyorAccumulation(c.getId()));
    }

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

    public void processLocationEntry(String itemId, String locationId, Instant timestamp) {
        var location = topologyProvider.getLocationById(locationId);
        if (location == null) return;

        if (location.getType() == LocationType.CHUTE) {
            liveLocationRepository.addItemToLocation(locationId, itemId);
        } else {
            // Not a chute, move to next conveyor
            String nextConveyorId = calculateNextConveyor(itemId, locationId, null);
            if (nextConveyorId != null) {
                // Update item position to the start of this conveyor
                itemService.updateItemPosition(itemId, nextConveyorId, PositionType.CONVEYOR, timestamp, 0.0, null);
                liveConveyorRepository.addItemToConveyor(nextConveyorId, itemId, timestamp);
                handleItemEntryToConveyor(itemId, nextConveyorId, timestamp, 0.0, locationId);
            }
        }
    }

    public void scheduleEvent(DomainEvent event) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null)
            simulationService.addInternalEvent(event);
        else
            liveSystemScheduler.scheduleInternalEvent(event);
    }

    public void cancelScheduledEvent(String itemId) {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null)
            simulationService.cancelInternalEvent(itemId);
        else
            liveSystemScheduler.cancelInternalEvent(itemId);
    }

    public String calculateNextConveyor(String itemId, String currentLocationId, String currentConveyorId) {
        var item = itemService.getItemById(itemId);
        if (item == null) return null;
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
            if (target != null && isNextSegmentBlocked(target)) {
                return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                        .orElse(targetConveyorId);
            }
            return targetConveyorId;
        }
        return outgoing.stream().filter(Conveyor::isMainPath).map(Conveyor::getId).findFirst()
                .orElse(outgoing.get(0).getId());
    }

    private String findRecirculationPath(String currentLocationId, String blockedConveyorId) {
        return topologyProvider.getOutgoingConveyors(currentLocationId).stream()
                .filter(Conveyor::isActive)
                .filter(c -> c.isMainPath())
                .filter(c -> !c.getId().equals(blockedConveyorId))
                .filter(c -> !isNextSegmentBlocked(c))
                .map(Conveyor::getId)
                .findFirst()
                .orElse(null);
    }
}
