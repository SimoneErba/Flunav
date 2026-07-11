package com.flunav.backend.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import flunav.types.PositionType;
import flunav.types.RoutingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedisLiveItem {
    private static final Logger logger = LoggerFactory.getLogger(RedisLiveItem.class);

    private String id;
    private String positionId; // Redis key: "e"
    private PositionType type; // Redis key: "ty"
    private Instant entryTime; // Redis key: "t"
    private Instant createdAt; // Redis key: "ct"
    private double accumulatedDistance; // Redis key: "ad"
    private List<String> destinations; // Redis key: "ds"
    private String selectedExitId; // Redis key: "d"
    private RoutingStatus routingStatus; // Redis key: "rs"
    private Instant routingStatusUpdatedAt; // Redis key: "rst"
    private String name; // Redis key: "n"
    private List<String> path; // Redis key: "p"

    /**
     * Converts the object to a Map for Redis Hash storage.
     */
    public Map<String, String> toRedisMap(ObjectMapper objectMapper) {
        Map<String, String> data = new HashMap<>();

        if (positionId != null)
            data.put("e", positionId);
        if (type != null)
            data.put("ty", type.name());
        if (entryTime != null)
            data.put("t", String.valueOf(entryTime.toEpochMilli()));
        if (createdAt != null)
            data.put("ct", String.valueOf(createdAt.toEpochMilli()));
        data.put("ad", String.valueOf(accumulatedDistance));

        if (destinations != null) {
            try {
                data.put("ds", objectMapper.writeValueAsString(destinations));
            } catch (JsonProcessingException e) {
                logger.warn("Failed to serialize destinations for item {}: {}", id, destinations);
            }
        }
        if (selectedExitId != null)
            data.put("d", selectedExitId);
        if (routingStatus != null)
            data.put("rs", routingStatus.name());
        if (routingStatusUpdatedAt != null)
            data.put("rst", String.valueOf(routingStatusUpdatedAt.toEpochMilli()));
        if (name != null)
            data.put("n", name);

        if (path != null) {
            try {
                data.put("p", objectMapper.writeValueAsString(path));
            } catch (JsonProcessingException e) {
                logger.warn("Failed to serialize path for item {}: {}", id, path);
            }
        }
        return data;
    }

    /**
     * Factory method to create an instance from Redis Hash data.
     */
    public static RedisLiveItem fromRedisMap(String id, Map<String, String> hash, ObjectMapper objectMapper) {
        if (hash == null || hash.isEmpty())
            return null;

        RedisLiveItemBuilder builder = RedisLiveItem.builder().id(id);

        // 1. Position ID
        if (hash.containsKey("e"))
            builder.positionId(hash.get("e"));

        // 2. Position Type
        String typeStr = hash.get("ty");
        if (typeStr != null) {
            try {
                builder.type(PositionType.valueOf(typeStr));
            } catch (IllegalArgumentException e) {
                builder.type(PositionType.LOCATION); // Default fallback
            }
        }

        // 3. Entry Time
        String timeStr = hash.get("t");
        if (timeStr != null) {
            try {
                builder.entryTime(Instant.ofEpochMilli(Long.parseLong(timeStr)));
            } catch (NumberFormatException ignored) {
            }
        }

        String createdAtStr = hash.get("ct");
        if (createdAtStr != null) {
            try {
                builder.createdAt(Instant.ofEpochMilli(Long.parseLong(createdAtStr)));
            } catch (NumberFormatException ignored) {
            }
        }

        // 4. Accumulated Distance
        String distStr = hash.get("ad");
        if (distStr != null) {
            try {
                builder.accumulatedDistance(Double.parseDouble(distStr));
            } catch (NumberFormatException ignored) {
            }
        }

        // 5. Navigation and simple strings
        String destinationsJson = hash.get("ds");
        if (destinationsJson != null && !destinationsJson.isEmpty()) {
            try {
                builder.destinations(objectMapper.readValue(destinationsJson, new TypeReference<List<String>>() {
                }));
            } catch (Exception e) {
                logger.warn("Failed to parse destinations JSON for item {}: {}", id, destinationsJson);
            }
        }
        if (hash.containsKey("d"))
            builder.selectedExitId(hash.get("d"));
        String routingStatusStr = hash.get("rs");
        if (routingStatusStr != null) {
            try {
                builder.routingStatus(RoutingStatus.valueOf(routingStatusStr));
            } catch (IllegalArgumentException ignored) {
                builder.routingStatus(null);
            }
        }
        String routingStatusTimeStr = hash.get("rst");
        if (routingStatusTimeStr != null) {
            try {
                builder.routingStatusUpdatedAt(Instant.ofEpochMilli(Long.parseLong(routingStatusTimeStr)));
            } catch (NumberFormatException ignored) {
            }
        }
        if (hash.containsKey("n"))
            builder.name(hash.get("n"));

        // 6. Path (JSON)
        String pathStr = hash.get("p");
        if (pathStr != null && !pathStr.isEmpty()) {
            try {
                List<String> pathList = objectMapper.readValue(pathStr, new TypeReference<List<String>>() {
                });
                builder.path(pathList);
            } catch (Exception e) {
                logger.warn("Failed to parse path JSON for item {}: {}", id, pathStr);
            }
        }

        return builder.build();
    }
}
