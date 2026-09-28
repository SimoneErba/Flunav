package com.flunav.backend.services;

import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.models.UpdateModel;
import com.flunav.backend.models.analytics.LocationTransitMetric;
import com.flunav.backend.repositories.LiveItemRepository;
import flunav.events.*;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import flunav.types.ActiveAlarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import com.flunav.backend.context.DatabaseContextHolder;

/** Domain reduction helpers; invoked only inside EventProcessor context and retry boundaries. */
@Service
final class EventReductionSupport {
    private static final Logger logger = LoggerFactory.getLogger(EventReductionSupport.class);
    private final ClickHouseService clickHouseService;
    private final ItemService itemService;
    private final WebSocketService webSocketService;
    private final LiveItemRepository liveItemRepository;
    private final TopologyProvider topologyProvider;
    private final boolean manageLogic;
    private final ItemMovementProcessor itemMovementProcessor;
    private final DestinationMappingService destinationMappingService;
    private final RoutingDecisionService routingDecisionService;
    private final RoutingCoordinator routingCoordinator;
    private final PathAssignmentPublisher pathAssignmentPublisher;
    private final OperationalAnalyticsService operationalAnalyticsService;

    EventReductionSupport(
            ClickHouseService clickHouseService,
            ItemService itemService,
            WebSocketService webSocketService,
            LiveItemRepository liveItemRepository,
            TopologyProvider topologyProvider,
            @Value("${simulation.manage-logic:true}") boolean manageLogic,
            ItemMovementProcessor itemMovementProcessor,
            DestinationMappingService destinationMappingService,
            RoutingDecisionService routingDecisionService,
            RoutingCoordinator routingCoordinator,
            PathAssignmentPublisher pathAssignmentPublisher,
            OperationalAnalyticsService operationalAnalyticsService) {
        this.clickHouseService = clickHouseService;
        this.itemService = itemService;
        this.webSocketService = webSocketService;
        this.liveItemRepository = liveItemRepository;
        this.topologyProvider = topologyProvider;
        this.manageLogic = manageLogic;
        this.itemMovementProcessor = itemMovementProcessor;
        this.destinationMappingService = destinationMappingService;
        this.routingDecisionService = routingDecisionService;
        this.routingCoordinator = routingCoordinator;
        this.pathAssignmentPublisher = pathAssignmentPublisher;
        this.operationalAnalyticsService = operationalAnalyticsService;
    }

    boolean hasStoppingAlarm(Conveyor conveyor) {
        return conveyor.getActiveAlarms() != null
                && conveyor.getActiveAlarms().stream().anyMatch(ActiveAlarm::isStopsConveyor);
    }

    List<String> alarmAffectedItems(DomainEvent event) {
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
     * Checkpoints all items on a conveyor before a speed-changing event takes
     * effect. The delegated processor stores distance in Redis so rescheduling uses
     * physical progress instead of stale percentages.
     */
    void checkpointItems(String edgeId, double oldSpeed, Instant timestamp) {
        itemMovementProcessor.checkpointItems(edgeId, oldSpeed, timestamp);
    }

    /** Freezes and cancels movement plans before a conveyor becomes inactive. */
    void checkpointBeforeConveyorStops(Conveyor conveyor, Instant timestamp, boolean shouldBroadcast) {
        if (conveyor.getType() == flunav.types.ConveyorType.STAGING) {
            itemMovementProcessor.checkpointStagingItems(conveyor, timestamp, true, shouldBroadcast);
        } else {
            checkpointItems(conveyor.getId(), conveyor.getSpeed(), timestamp);
        }
    }

    /** Rebuilds movement schedules only when effective conveyor activity changes. */
    void updateMovementAfterActivityChange(Conveyor conveyor, boolean wasActive, Instant timestamp,
            boolean shouldBroadcast) {
        if (!manageLogic || wasActive == conveyor.isActive()) {
            return;
        }
        if (conveyor.isActive()) {
            itemMovementProcessor.resumeConveyorFromCheckpoints(conveyor.getId(), timestamp, shouldBroadcast);
        } else {
            itemMovementProcessor.recalculateConveyorAccumulation(conveyor.getId());
        }
        itemMovementProcessor.wakeUpPrecedingConveyors(conveyor.getSourceLocationId());
    }

    /**
     * Records live conveyor transit facts after the movement reducer succeeds.
     * The raw fact is enough for ClickHouse views to maintain path timing baselines
     * without replaying the full event stream for every analytics refresh.
     */
    void recordCompletedTransit(
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

    String currentSimulationScope() {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId != null ? simulationId : "live";
    }

    /**
     * Reattempts route assignment for priority items waiting on chute capacity.
     * The routing lock serializes retries so newly opened capacity is assigned in
     * waiting order without overbooking projected occupancy.
     */
    void retryWaitingHighPriorityItems(Instant timestamp, boolean shouldBroadcast) {
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

    void addRushFields(Map<String, Object> updates, com.flunav.backend.domain.Item item, Instant timestamp) {
        DestinationMappingService.RushPriority rush = destinationMappingService.evaluateRush(item, timestamp);
        updates.put("effectivePriority", rush.effectivePriority());
        updates.put("rushActive", rush.rushActive());
    }

}
