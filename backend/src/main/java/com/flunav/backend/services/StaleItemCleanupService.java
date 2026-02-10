package com.flunav.backend.services;

import com.flunav.backend.models.RedisLiveItem;
import com.flunav.backend.repositories.LiveItemRepository;
import flunav.types.LocationType;
import flunav.types.PositionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "stale-item-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class StaleItemCleanupService {
    private static final Logger logger = LoggerFactory.getLogger(StaleItemCleanupService.class);
    private final LiveItemRepository liveItemRepository;
    private final LocationService locationService;

    public StaleItemCleanupService(LiveItemRepository liveItemRepository, LocationService locationService) {
        this.liveItemRepository = liveItemRepository;
        this.locationService = locationService;
    }

    @Scheduled(fixedRate = 300_000) // Run every 5 minutes
    public void cleanupStaleItemsAtExits() {
        logger.info("Running stale item cleanup job...");
        int processedCount = 0;
        int removedCount = 0;
        Instant cutoffTime = Instant.now().minus(Duration.ofMinutes(10));

        try {
            List<RedisLiveItem> allActiveItems = liveItemRepository.getAllActiveItems();

            for (RedisLiveItem itemData : allActiveItems) {
                String itemId = itemData.getId();
                String positionId = itemData.getPositionId();
                Instant lastUpdate = itemData.getEntryTime();

                processedCount++;

                if (lastUpdate == null || positionId == null) {
                    continue;
                }

                // Check if item is stale (no update for 10 minutes)
                if (lastUpdate.isAfter(cutoffTime)) {
                    continue;
                }

                // Check if item is at an exit point (CHUTE)
                try {
                    com.flunav.backend.domain.Location location = locationService.getLocationById(positionId);
                    if (location != null && location.getType() == LocationType.CHUTE) {
                        // Stale item at CHUTE - it likely exited
                        logger.info("Removing stale item {} at CHUTE {} (last update: {})",
                                itemId, positionId, lastUpdate);

                        liveItemRepository.deleteItem(itemId);
                        removedCount++;
                    }
                } catch (Exception e) {
                    logger.warn("Could not check location {} for item {}", positionId, itemId, e);
                }
            }

            if (removedCount > 0) {
                logger.info("Stale item cleanup complete. Processed: {}, Removed: {}",
                        processedCount, removedCount);
            } else {
                logger.debug("Stale item cleanup complete. No items to remove. Processed: {}",
                        processedCount);
            }

        } catch (Exception e) {
            logger.error("Error during stale item cleanup", e);
        }
    }
}
