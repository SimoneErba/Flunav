package com.flunav.backend.repositories;

import com.flunav.backend.models.simulation.SimulationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class LiveSimulationRepository {
    private static final Logger logger = LoggerFactory.getLogger(LiveSimulationRepository.class);

    private final StringRedisTemplate redis;

    public LiveSimulationRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void saveState(SimulationMetadata metadata) {
        redis.opsForHash().putAll(getStateKey(metadata.simulationId()), metadata.toRedisMap());
    }

    public Optional<SimulationMetadata> getState(String simulationId) {
        Map<String, String> raw = redis.<String, String>opsForHash().entries(getStateKey(simulationId));
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(SimulationMetadata.fromRedisMap(simulationId, raw));
        } catch (RuntimeException e) {
            logger.warn("Skipping invalid simulation metadata for {}: {}", simulationId, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean exists(String simulationId) {
        Boolean exists = redis.hasKey(getStateKey(simulationId));
        return Boolean.TRUE.equals(exists);
    }

    public List<SimulationHeartbeat> getAllSimulationHeartbeats() {
        Set<String> keys = redis.keys("sim:*:state");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> sortedKeys = new ArrayList<>(keys);
        Collections.sort(sortedKeys);

        List<SimulationHeartbeat> heartbeats = new ArrayList<>();
        for (String key : sortedKeys) {
            String simulationId = extractSimulationId(key);
            getState(simulationId)
                    .map(SimulationMetadata::lastHeartbeatTimestamp)
                    .ifPresent(heartbeat -> heartbeats.add(new SimulationHeartbeat(simulationId, heartbeat)));
        }

        return heartbeats;
    }

    public void deleteState(String simulationId) {
        redis.delete(getStateKey(simulationId));
    }

    private String getStateKey(String simulationId) {
        return "sim:" + simulationId + ":state";
    }

    private String extractSimulationId(String stateKey) {
        return stateKey.substring(4, stateKey.length() - ":state".length());
    }

    public record SimulationHeartbeat(String simulationId, Instant lastHeartbeatTimestamp) {
    }

    public record SimulationMetadata(String simulationId, Instant timestamp, SimulationStatus status,
            Instant lastHeartbeatTimestamp, Instant lastProcessedTimestamp, double speedFactor, double buildProgress) {

        public SimulationMetadata(String simulationId, Instant timestamp, SimulationStatus status,
                Instant lastHeartbeatTimestamp, Instant lastProcessedTimestamp, double speedFactor) {
            this(simulationId, timestamp, status, lastHeartbeatTimestamp, lastProcessedTimestamp, speedFactor,
                    status == SimulationStatus.READY ? 100.0 : 0.0);
        }

        public Map<String, String> toRedisMap() {
            var map = new java.util.HashMap<String, String>();
            putInstant(map, "ts", timestamp);
            map.put("st", status.name());
            putInstant(map, "hb", lastHeartbeatTimestamp);
            putInstant(map, "lp", lastProcessedTimestamp);
            map.put("sp", String.valueOf(speedFactor));
            map.put("bp", String.valueOf(buildProgress));
            return map;
        }

        public static SimulationMetadata fromRedisMap(String simulationId, Map<String, String> raw) {
            return new SimulationMetadata(
                    simulationId,
                    parseInstant(raw.get("ts")),
                    SimulationStatus.valueOf(raw.getOrDefault("st", SimulationStatus.QUEUED.name())),
                    parseInstant(raw.get("hb")),
                    parseInstant(raw.get("lp")),
                    raw.containsKey("sp") ? Double.parseDouble(raw.get("sp")) : 1.0,
                    raw.containsKey("bp")
                            ? Double.parseDouble(raw.get("bp"))
                            : defaultBuildProgress(raw.get("st")));
        }

        private static double defaultBuildProgress(String rawStatus) {
            return SimulationStatus.READY.name().equals(rawStatus) ? 100.0 : 0.0;
        }

        private static void putInstant(Map<String, String> map, String key, Instant value) {
            if (value != null) {
                map.put(key, String.valueOf(value.toEpochMilli()));
            }
        }

        private static Instant parseInstant(String raw) {
            return raw != null ? Instant.ofEpochMilli(Long.parseLong(raw)) : null;
        }
    }
}
