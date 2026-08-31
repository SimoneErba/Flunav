package com.flunav.backend.repositories;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.analytics.AnomalyFinding;
import com.flunav.backend.models.analytics.AnomalyIncident;
import com.flunav.backend.models.analytics.DetectorBaseline;
import com.flunav.backend.models.analytics.LocationFlowObservation;
import com.flunav.backend.models.analytics.PositionTransitionObservation;
import com.flunav.backend.models.analytics.TransitObservation;

import flunav.events.AnomalyEvaluationTickEvent.Cadence;

@Repository
public class AnomalyObservationRepository {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AnomalyObservationRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void appendTransition(PositionTransitionObservation observation) {
        append("transition", observation.timestamp(), observation);
    }

    public void appendTransit(TransitObservation observation) {
        append("transit", observation.timestamp(), observation);
    }

    public void appendFlow(LocationFlowObservation observation) {
        append("flow", observation.timestamp(), observation);
    }

    public List<PositionTransitionObservation> transitions(Instant afterExclusive, Instant throughInclusive) {
        return readWindow("transition", afterExclusive, throughInclusive, PositionTransitionObservation.class);
    }

    public List<TransitObservation> transits(Instant afterExclusive, Instant throughInclusive) {
        return readWindow("transit", afterExclusive, throughInclusive, TransitObservation.class);
    }

    public List<LocationFlowObservation> flows(Instant afterExclusive, Instant throughInclusive) {
        return readWindow("flow", afterExclusive, throughInclusive, LocationFlowObservation.class);
    }

    public Instant getLastBoundary(Cadence cadence) {
        String value = redis.opsForValue().get(key("boundary:" + cadence.name().toLowerCase()));
        return value != null ? Instant.ofEpochMilli(Long.parseLong(value)) : null;
    }

    public void setLastBoundary(Cadence cadence, Instant boundary) {
        redis.opsForValue().set(key("boundary:" + cadence.name().toLowerCase()),
                String.valueOf(boundary.toEpochMilli()));
    }

    public void initializeForkTimestamp(Instant timestamp) {
        redis.opsForValue().setIfAbsent(key("fork-timestamp"), String.valueOf(timestamp.toEpochMilli()));
    }

    public Instant getForkTimestamp() {
        String value = redis.opsForValue().get(key("fork-timestamp"));
        return value != null ? Instant.ofEpochMilli(Long.parseLong(value)) : null;
    }

    public Map<Object, Object> getJamStates() {
        return redis.opsForHash().entries(key("jam-state"));
    }

    public void putJamState(String componentKey, String value) {
        redis.opsForHash().put(key("jam-state"), componentKey, value);
    }

    public void setTimingEpoch(String conveyorId, Instant timestamp) {
        redis.opsForHash().put(key("timing-epochs"), conveyorId, String.valueOf(timestamp.toEpochMilli()));
    }

    public Instant getTimingEpoch(String conveyorId) {
        Object value = redis.opsForHash().get(key("timing-epochs"), conveyorId);
        return value != null ? Instant.ofEpochMilli(Long.parseLong(value.toString())) : Instant.EPOCH;
    }

    public void putBaseline(String cacheKey, DetectorBaseline baseline) {
        redis.opsForHash().put(key("baselines"), cacheKey, json(baseline));
    }

    public DetectorBaseline getBaseline(String cacheKey) {
        Object value = redis.opsForHash().get(key("baselines"), cacheKey);
        return value != null ? fromJson(value.toString(), DetectorBaseline.class) : null;
    }

    public List<DetectorBaseline> getBaselines() {
        List<DetectorBaseline> result = new ArrayList<>();
        for (Object value : redis.opsForHash().values(key("baselines"))) {
            DetectorBaseline baseline = fromJson(value.toString(), DetectorBaseline.class);
            if (baseline != null) {
                result.add(baseline);
            }
        }
        return result;
    }

    public void replaceBaselines(Map<String, DetectorBaseline> baselines) {
        String staging = key("baselines:staging");
        redis.delete(staging);
        redis.opsForHash().put(staging, "__refresh__", "1");
        baselines.forEach((cacheKey, baseline) -> redis.opsForHash().put(staging, cacheKey, json(baseline)));
        redis.rename(staging, key("baselines"));
        redis.opsForHash().delete(key("baselines"), "__refresh__");
    }

    public void saveFinding(AnomalyFinding finding) {
        String findingsKey = key("findings");
        Set<String> existing = redis.opsForZSet().rangeByScore(findingsKey,
                finding.tickTimestamp().toEpochMilli(), finding.tickTimestamp().toEpochMilli());
        if (existing != null) {
            existing.stream()
                    .filter(value -> value.contains("\"findingId\":\"" + finding.findingId() + "\""))
                    .forEach(value -> redis.opsForZSet().remove(findingsKey, value));
        }
        redis.opsForZSet().add(findingsKey, json(finding), finding.tickTimestamp().toEpochMilli());
    }

    public List<AnomalyFinding> getFindings(Instant from, Instant to) {
        return readScoreWindow("findings", from, to, AnomalyFinding.class);
    }

    public AnomalyFinding getFinding(String findingId) {
        return getFindings(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE)).stream()
                .filter(finding -> finding.findingId().equals(findingId))
                .findFirst()
                .orElse(null);
    }

    public void saveIncident(AnomalyIncident incident) {
        redis.opsForHash().put(key("incidents"), incident.incidentId(), json(incident));
    }

    public List<AnomalyIncident> getIncidents() {
        List<AnomalyIncident> result = new ArrayList<>();
        for (Object value : redis.opsForHash().values(key("incidents"))) {
            AnomalyIncident incident = fromJson(value.toString(), AnomalyIncident.class);
            if (incident != null) {
                result.add(incident);
            }
        }
        result.sort(java.util.Comparator.comparing(AnomalyIncident::updatedAt).reversed());
        return result;
    }

    public AnomalyIncident getIncident(String incidentId) {
        Object value = redis.opsForHash().get(key("incidents"), incidentId);
        return value != null ? fromJson(value.toString(), AnomalyIncident.class) : null;
    }

    public void appendThroughput(String componentKey, Instant minute, long throughput) {
        redis.opsForZSet().add(key("throughput:" + componentKey), minute.toEpochMilli() + ":" + throughput,
                minute.toEpochMilli());
        redis.opsForZSet().removeRangeByScore(key("throughput:" + componentKey), 0,
                minute.minusSeconds(8 * 24 * 3600L).toEpochMilli());
    }

    public List<Long> latestThroughput(String componentKey, Instant through, int minutes) {
        long start = through.minusSeconds(minutes * 60L).toEpochMilli() + 1;
        Set<String> values = redis.opsForZSet().rangeByScore(key("throughput:" + componentKey), start,
                through.toEpochMilli());
        if (values == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (String value : values) {
            int separator = value.lastIndexOf(':');
            result.add(Long.parseLong(value.substring(separator + 1)));
        }
        return result;
    }

    public void cleanupSimulationData(String simulationId) {
        if (simulationId == null) {
            return;
        }
        Set<String> keys = redis.keys("sim:" + simulationId + ":anomaly:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void append(String type, Instant timestamp, Object observation) {
        redis.opsForZSet().add(key("observations:" + type), json(observation), timestamp.toEpochMilli());
    }

    private <T> List<T> readWindow(String type, Instant afterExclusive, Instant throughInclusive, Class<T> typeClass) {
        long minimum = afterExclusive != null ? afterExclusive.toEpochMilli() + 1 : Long.MIN_VALUE;
        Set<String> values = redis.opsForZSet().rangeByScore(key("observations:" + type), minimum,
                throughInclusive.toEpochMilli());
        return decode(values, typeClass);
    }

    private <T> List<T> readScoreWindow(String suffix, Instant from, Instant to, Class<T> typeClass) {
        Set<String> values = redis.opsForZSet().reverseRangeByScore(key(suffix), from.toEpochMilli(), to.toEpochMilli());
        return decode(values, typeClass);
    }

    private <T> List<T> decode(Set<String> values, Class<T> typeClass) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        for (String value : values) {
            T decoded = fromJson(value, typeClass);
            if (decoded != null) {
                result.add(decoded);
            }
        }
        return result;
    }

    private String key(String suffix) {
        String simulationId = DatabaseContextHolder.getSimulationId();
        return simulationId != null
                ? "sim:" + simulationId + ":anomaly:" + suffix
                : "anomaly:" + suffix;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize anomaly state", e);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize anomaly state", e);
        }
    }
}
