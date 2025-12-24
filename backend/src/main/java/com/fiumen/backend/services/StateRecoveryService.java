package com.fiumen.backend.services;

import com.fiumen.backend.repositories.LiveItemRepository;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import fiumen.types.PositionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StateRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(StateRecoveryService.class);

    private final LiveItemRepository redisRepo;
    private final ClickHouseService clickHouseService;
    private final OrientDBService orientDBService;

    public StateRecoveryService(LiveItemRepository redisRepo,
            ClickHouseService clickHouseService,
            OrientDBService orientDBService) {
        this.redisRepo = redisRepo;
        this.clickHouseService = clickHouseService;
        this.orientDBService = orientDBService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            // 1. Check if Redis is empty
            long activeCount = redisRepo.countActiveItems();
            if (activeCount > 0) {
                logger.info("Redis already contains {} active items. Skipping rehydration.", activeCount);
                return;
            }

            logger.warn("Redis is empty! Starting State Rehydration from ClickHouse...");

            // 2. Fetch last known positions from ClickHouse
            String sql = """
                        SELECT
                            entity_id as itemId,
                            argMaxIf(data.locationId, timestamp_processed, event_type IN ('ITEM_CREATED', 'ITEM_POSITION_CHANGED')) as edge,
                            maxIf(timestamp_processed, event_type IN ('ITEM_CREATED', 'ITEM_POSITION_CHANGED')) as ts
                        FROM Events
                        GROUP BY entity_id
                        HAVING argMax(event_type, timestamp_processed) NOT IN ('ITEM_DELETED', 'ITEM_DEACTIVATED')
                           AND edge IS NOT NULL
                    """;

            List<Map<String, Object>> rows = clickHouseService.queryForList(sql);

            if (rows.isEmpty()) {
                logger.info("No active items found in ClickHouse history.");
                return;
            }

            // 3. Fetch Names from OrientDB (Bulk Query)
            // We extract all IDs first to do a single query instead of N queries.
            Set<String> itemIds = rows.stream()
                    .map(row -> (String) row.get("itemId"))
                    .collect(Collectors.toSet());

            Map<String, String> namesMap = fetchNamesFromOrientDB(itemIds);
            var locationTypesMap = fetchPositionTypesFromOrientDB(itemIds);
            // 4. Bulk load into Redis
            int count = 0;
            for (Map<String, Object> row : rows) {
                String id = (String) row.get("itemId");
                String edge = (String) row.get("edge");
                Object tsObj = row.get("ts");

                Instant timestamp = parseTimestamp(tsObj);

                if (id != null && edge != null && timestamp != null) {
                    // Retrieve name from our pre-fetched map
                    String name = namesMap.get(id);
                    var type = locationTypesMap.get(id);
                    // Save with Name (if found)
                    redisRepo.saveItemState(id, edge, type, timestamp, null, name);
                    count++;
                }
            }

            logger.info("Rehydration complete. Restored {} items (with names) from history.", count);

        } catch (Exception e) {
            logger.error("CRITICAL: Failed to rehydrate state. System starting with empty state.", e);
        }
    }

    /**
     * Queries OrientDB to get the 'name' property for a list of Item IDs.
     */
    private Map<String, String> fetchNamesFromOrientDB(Set<String> itemIds) {
        Map<String, String> result = new HashMap<>();
        if (itemIds.isEmpty())
            return result;

        try (ODatabaseSession session = orientDBService.getSession()) {
            // OrientDB supports passing a Collection as a parameter for IN operator
            String query = "SELECT customId, name FROM Item WHERE customId IN :ids";

            try (OResultSet rs = session.query(query, Map.of("ids", itemIds))) {
                while (rs.hasNext()) {
                    OResult item = rs.next();
                    String id = item.getProperty("customId");
                    String name = item.getProperty("name");
                    if (id != null && name != null) {
                        result.put(id, name);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch item names from OrientDB during rehydration. Items will appear without names.",
                    e);
        }
        return result;
    }

    /**
     * Bulk determines if IDs are Locations or Conveyors.
     * Returns a Map<ID, PositionType>.
     */
    private Map<String, PositionType> fetchPositionTypesFromOrientDB(Set<String> ids) {
        Map<String, PositionType> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }

        try (ODatabaseSession session = orientDBService.getSession()) {
            Map<String, Object> params = Map.of("ids", ids);

            String locQuery = "SELECT customId FROM Location WHERE customId IN :ids";
            try (OResultSet rs = session.query(locQuery, params)) {
                while (rs.hasNext()) {
                    OResult item = rs.next();
                    String id = item.getProperty("customId");
                    if (id != null) {
                        result.put(id, PositionType.LOCATION);
                    }
                }
            }

            String convQuery = "SELECT customId FROM Conveyor WHERE customId IN :ids";
            try (OResultSet rs = session.query(convQuery, params)) {
                while (rs.hasNext()) {
                    OResult item = rs.next();
                    String id = item.getProperty("customId");
                    if (id != null) {
                        result.put(id, PositionType.CONVEYOR);
                    }
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to fetch position types in bulk.", e);
        }
        return result;
    }

    private Instant parseTimestamp(Object tsObj) {
        if (tsObj == null)
            return Instant.now();
        try {
            if (tsObj instanceof Timestamp)
                return ((Timestamp) tsObj).toInstant();
            if (tsObj instanceof LocalDateTime)
                return ((LocalDateTime) tsObj).toInstant(ZoneOffset.UTC);
            if (tsObj instanceof String)
                return Timestamp.valueOf((String) tsObj).toInstant();
            if (tsObj instanceof Number)
                return Instant.ofEpochMilli(((Number) tsObj).longValue());
        } catch (Exception e) {
            logger.warn("Could not parse timestamp: {}. Using current time.", tsObj);
        }
        return Instant.now();
    }
}