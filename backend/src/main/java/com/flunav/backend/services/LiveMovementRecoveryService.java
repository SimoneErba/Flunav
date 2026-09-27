package com.flunav.backend.services;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.domain.Conveyor;
import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.repositories.LiveConveyorRepository;
import com.flunav.backend.repositories.LiveItemRepository;
import flunav.types.PositionType;
import flunav.events.ItemPositionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class LiveMovementRecoveryService {
    private static final Logger logger = LoggerFactory.getLogger(LiveMovementRecoveryService.class);

    private final LiveItemRepository liveItemRepository;
    private final LiveConveyorRepository liveConveyorRepository;
    private final TopologyProvider topologyProvider;
    private final ItemMovementProcessor itemMovementProcessor;
    private final TimeService timeService;

    public LiveMovementRecoveryService(
            LiveItemRepository liveItemRepository,
            LiveConveyorRepository liveConveyorRepository,
            TopologyProvider topologyProvider,
            ItemMovementProcessor itemMovementProcessor,
            TimeService timeService) {
        this.liveItemRepository = liveItemRepository;
        this.liveConveyorRepository = liveConveyorRepository;
        this.topologyProvider = topologyProvider;
        this.itemMovementProcessor = itemMovementProcessor;
        this.timeService = timeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 50)
    public void recoverOnStartup() {
        recoverLiveMovementSchedules();
    }

    /**
     * Recreates live scheduled movement events from Redis after startup.
     * The method explicitly enters live context so any leftover simulation
     * ThreadLocal state cannot route recovery writes into a simulation namespace.
     */
    public void recoverLiveMovementSchedules() {
        try (var ignored = DatabaseContextHolder.enterSimulationContext(null)) {
            Instant recoveryTime = timeService.physicalNow();
            List<RedisLiveItem> activeItems = liveItemRepository.getAllActiveItems();
            Set<String> conveyorIds = restoreConveyorMembership(activeItems);

            int recoveredItems = 0;
            for (String conveyorId : conveyorIds) {
                Set<String> orderedItemIds = liveConveyorRepository.getItemsOrderedByDistance(conveyorId);
                for (String itemId : orderedItemIds) {
                    RedisLiveItem item = liveItemRepository.getItemState(itemId);
                    if (!isOnConveyor(item, conveyorId)) {
                        liveConveyorRepository.removeItemFromConveyor(conveyorId, itemId);
                        continue;
                    }

                    if (recoverItem(item, recoveryTime)) {
                        recoveredItems++;
                    }
                }
            }

            if (recoveredItems > 0) {
                logger.info("Recovered live movement schedules for {} items across {} conveyors.",
                        recoveredItems, conveyorIds.size());
            }
        } catch (Exception e) {
            logger.error("Failed to recover live movement schedules from Redis.", e);
        }
    }

    /**
     * Rebuilds conveyor ordered sets from item hashes.
     * Item hashes are the durable hot-state record after restart, while conveyor
     * membership sets may have expired or been lost independently.
     */
    private Set<String> restoreConveyorMembership(List<RedisLiveItem> activeItems) {
        Set<String> conveyorIds = new LinkedHashSet<>();
        for (RedisLiveItem item : activeItems) {
            if (item.getType() != PositionType.CONVEYOR
                    || item.getPositionId() == null
                    || item.getEntryTime() == null) {
                continue;
            }

            Conveyor conveyor = topologyProvider.getConveyorById(item.getPositionId());
            if (conveyor == null) {
                logger.warn("Cannot recover movement for item {} because conveyor {} does not exist.",
                        item.getId(), item.getPositionId());
                continue;
            }

            liveConveyorRepository.addItemToConveyor(item.getPositionId(), item.getId(), item.getEntryTime());
            conveyorIds.add(item.getPositionId());
        }
        return conveyorIds;
    }

    /**
     * Recomputes the item's current conveyor distance and schedules its next event.
     * Items still on the conveyor are checkpointed at recovery time; items that
     * should already have arrived are scheduled from their original checkpoint.
     */
    private boolean recoverItem(RedisLiveItem item, Instant recoveryTime) {
        Conveyor conveyor = topologyProvider.getConveyorById(item.getPositionId());
        if (conveyor == null || conveyor.getLength() == null || conveyor.getLength() <= 0) {
            return false;
        }
        if (item.isMovementPaused()) return false;

        if (item.getPlannedPositionId() != null && item.getPlannedTransitionTimestamp() != null) {
            double progress = item.getPlannedPositionType() == PositionType.CONVEYOR ? 0.0 : 100.0;
            itemMovementProcessor.scheduleEvent(new ItemPositionChangedEvent(
                    item.getId(), item.getPlannedPositionId(), progress, item.getPlannedTransitionTimestamp()));
            return true;
        }

        if (item.getMovementCheckTimestamp() != null) {
            Instant retryAt = item.getMovementCheckTimestamp().isBefore(recoveryTime)
                    ? recoveryTime.plusMillis(1) : item.getMovementCheckTimestamp();
            itemMovementProcessor.scheduleMovementCheck(item.getId(), item.getPositionId(), retryAt);
            return true;
        }

        if (item.isFlowPaused()) {
            if (liveConveyorRepository.isFlowStopped(conveyor.getId())) return false;
            itemMovementProcessor.scheduleMovementCheck(item.getId(), item.getPositionId(),
                    recoveryTime.plusMillis(1000));
            return true;
        }

        double storedProgress = Math.min(100.0, Math.max(0.0, item.getAccumulatedDistance()));
        double speed = conveyor.getSpeed() != null ? conveyor.getSpeed() : 0.0;
        long elapsedMillis = Math.max(0L, recoveryTime.toEpochMilli() - item.getEntryTime().toEpochMilli());
        double progressDelta = liveConveyorRepository.isFlowStopped(conveyor.getId()) ? 0.0
                : (elapsedMillis / 1000.0) * Math.max(0.0, speed) / conveyor.getLength() * 100.0;
        double currentProgress = storedProgress + progressDelta;

        Instant schedulingTime = item.getEntryTime();
        double schedulingProgress = storedProgress;
        if (speed <= 0 || currentProgress < 100.0) {
            schedulingTime = recoveryTime;
            schedulingProgress = currentProgress;
            liveItemRepository.checkpointPhysics(item.getId(), recoveryTime, currentProgress);
        }

        itemMovementProcessor.handleItemEntryToConveyor(
                item.getId(),
                item.getPositionId(),
                schedulingTime,
                schedulingProgress,
                null);
        return true;
    }

    private boolean isOnConveyor(RedisLiveItem item, String conveyorId) {
        return item != null
                && item.getType() == PositionType.CONVEYOR
                && conveyorId.equals(item.getPositionId());
    }
}
