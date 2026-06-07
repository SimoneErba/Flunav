package com.flunav.backend.services;

import com.flunav.backend.repositories.LiveItemRepository;

import flunav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "state-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class StateRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(StateRecoveryService.class);

    private final LiveItemRepository liveItemRepository;
    private final ClickHouseService clickHouseService;
    private final HistoricalGraphBuilder historicalGraphBuilder;
    private final EventProcessor eventProcessor;

    public StateRecoveryService(LiveItemRepository liveItemRepository,
            ClickHouseService clickHouseService,
            HistoricalGraphBuilder historicalGraphBuilder,
            EventProcessor eventProcessor) {
        this.liveItemRepository = liveItemRepository;
        this.clickHouseService = clickHouseService;
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.eventProcessor = eventProcessor;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    public void onStartup() {
        try {
            long activeCount = liveItemRepository.countActiveItems();
            if (activeCount > 0) {
                logger.info("Redis already contains {} active items. Skipping live state restore.", activeCount);
                return;
            }

            Instant restorePoint = Instant.now();
            Optional<ClickHouseService.Snapshot> snapshotOpt = clickHouseService.getMostRecentSnapshotBefore(restorePoint);
            if (snapshotOpt.isEmpty()) {
                logger.warn("Redis is empty and no snapshot is available. Skipping live state restore.");
                return;
            }

            ClickHouseService.Snapshot snapshot = snapshotOpt.get();
            logger.info("Restoring live state from snapshot taken at {}", snapshot.timestamp());

            liveItemRepository.deleteAllItems();
            historicalGraphBuilder.restoreFromSnapshotData(snapshot.graphData());

            List<DomainEvent> eventsToReplay = clickHouseService.getEventsBetween(snapshot.timestamp(), restorePoint);
            logger.info("Replaying {} events from snapshot timestamp {} to {}", eventsToReplay.size(),
                    snapshot.timestamp(), restorePoint);

            for (DomainEvent event : eventsToReplay) {
                try {
                    eventProcessor.processEventWithoutBroadcast(event);
                } catch (Exception e) {
                    logger.warn("Error while replaying event {} during live restore. Skipping it. Error: {}",
                            event.getEventType(), e.getMessage());
                }
            }

            logger.info("Live state restore completed successfully.");
        } catch (Exception e) {
            logger.error("CRITICAL: Failed to restore live state from snapshot.", e);
        }
    }
}
