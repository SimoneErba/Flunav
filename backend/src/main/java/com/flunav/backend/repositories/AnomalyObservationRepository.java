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
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;
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
    private final MultiSimulationRuntimeStore runtimeStore;

    public AnomalyObservationRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
            MultiSimulationRuntimeStore runtimeStore) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.runtimeStore = runtimeStore;
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
        String value = valueGet(key("boundary:" + cadence.name().toLowerCase()));
        return value != null ? Instant.ofEpochMilli(Long.parseLong(value)) : null;
    }

    public void setLastBoundary(Cadence cadence, Instant boundary) {
        valueSet(key("boundary:" + cadence.name().toLowerCase()), String.valueOf(boundary.toEpochMilli()));
    }

    public void initializeForkTimestamp(Instant timestamp) {
        valueSetIfAbsent(key("fork-timestamp"), String.valueOf(timestamp.toEpochMilli()));
    }

    public Instant getForkTimestamp() {
        String value = valueGet(key("fork-timestamp"));
        return value != null ? Instant.ofEpochMilli(Long.parseLong(value)) : null;
    }

    public Map<Object, Object> getJamStates() {
        return new java.util.HashMap<>(hashEntries(key("jam-state")));
    }

    public void putJamState(String componentKey, String value) {
        hashPut(key("jam-state"), componentKey, value);
    }

    public void setTimingEpoch(String conveyorId, Instant timestamp) {
        hashPut(key("timing-epochs"), conveyorId, String.valueOf(timestamp.toEpochMilli()));
    }

    public Instant getTimingEpoch(String conveyorId) {
        Object value = hashGet(key("timing-epochs"), conveyorId);
        return value != null ? Instant.ofEpochMilli(Long.parseLong(value.toString())) : Instant.EPOCH;
    }

    public void putBaseline(String cacheKey, DetectorBaseline baseline) {
        hashPut(key("baselines"), cacheKey, json(baseline));
    }

    public DetectorBaseline getBaseline(String cacheKey) {
        Object value = hashGet(key("baselines"), cacheKey);
        return value != null ? fromJson(value.toString(), DetectorBaseline.class) : null;
    }

    public List<DetectorBaseline> getBaselines() {
        List<DetectorBaseline> result = new ArrayList<>();
        for (Object value : hashValues(key("baselines"))) {
            DetectorBaseline baseline = fromJson(value.toString(), DetectorBaseline.class);
            if (baseline != null) {
                result.add(baseline);
            }
        }
        return result;
    }

    public void replaceBaselines(Map<String, DetectorBaseline> baselines) {
        String staging = key("baselines:staging");
        delete(staging);
        hashPut(staging, "__refresh__", "1");
        baselines.forEach((cacheKey, baseline) -> hashPut(staging, cacheKey, json(baseline)));
        rename(staging, key("baselines"));
        hashDelete(key("baselines"), "__refresh__");
    }

    public void saveFinding(AnomalyFinding finding) {
        String findingsKey = key("findings");
        Set<String> existing = sortedRange(findingsKey, finding.tickTimestamp().toEpochMilli(),
                finding.tickTimestamp().toEpochMilli(), false);
        if (existing != null) {
            existing.stream()
                    .filter(value -> value.contains("\"findingId\":\"" + finding.findingId() + "\""))
                    .forEach(value -> sortedRemove(findingsKey, value));
        }
        sortedAdd(findingsKey, json(finding), finding.tickTimestamp().toEpochMilli());
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
        hashPut(key("incidents"), incident.incidentId(), json(incident));
    }

    public List<AnomalyIncident> getIncidents() {
        List<AnomalyIncident> result = new ArrayList<>();
        for (Object value : hashValues(key("incidents"))) {
            AnomalyIncident incident = fromJson(value.toString(), AnomalyIncident.class);
            if (incident != null) {
                result.add(incident);
            }
        }
        result.sort(java.util.Comparator.comparing(AnomalyIncident::updatedAt).reversed());
        return result;
    }

    public AnomalyIncident getIncident(String incidentId) {
        Object value = hashGet(key("incidents"), incidentId);
        return value != null ? fromJson(value.toString(), AnomalyIncident.class) : null;
    }

    public void appendThroughput(String componentKey, Instant minute, long throughput) {
        String throughputKey = key("throughput:" + componentKey);
        sortedAdd(throughputKey, minute.toEpochMilli() + ":" + throughput, minute.toEpochMilli());
        sortedRemoveByScore(throughputKey, 0, minute.minusSeconds(8 * 24 * 3600L).toEpochMilli());
    }

    public List<Long> latestThroughput(String componentKey, Instant through, int minutes) {
        long start = through.minusSeconds(minutes * 60L).toEpochMilli() + 1;
        Set<String> values = sortedRange(key("throughput:" + componentKey), start, through.toEpochMilli(), false);
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
        var memory = runtimeStore.get(simulationId);
        if (memory != null) {
            memory.deleteMatching("sim:" + simulationId + ":anomaly:");
            return;
        }
        Set<String> keys = redis.keys("sim:" + simulationId + ":anomaly:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void append(String type, Instant timestamp, Object observation) {
        sortedAdd(key("observations:" + type), json(observation), timestamp.toEpochMilli());
    }

    private <T> List<T> readWindow(String type, Instant afterExclusive, Instant throughInclusive, Class<T> typeClass) {
        long minimum = afterExclusive != null ? afterExclusive.toEpochMilli() + 1 : Long.MIN_VALUE;
        Set<String> values = sortedRange(key("observations:" + type), minimum,
                throughInclusive.toEpochMilli(), false);
        return decode(values, typeClass);
    }

    private <T> List<T> readScoreWindow(String suffix, Instant from, Instant to, Class<T> typeClass) {
        Set<String> values = sortedRange(key(suffix), from.toEpochMilli(), to.toEpochMilli(), true);
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

    private String valueGet(String key) {
        var memory = runtimeStore.current();
        return memory != null ? memory.getValue(key) : redis.opsForValue().get(key);
    }

    private void valueSet(String key, String value) {
        var memory = runtimeStore.current();
        if (memory != null) memory.setValue(key, value); else redis.opsForValue().set(key, value);
    }

    private void valueSetIfAbsent(String key, String value) {
        var memory = runtimeStore.current();
        if (memory != null) memory.setValueIfAbsent(key, value); else redis.opsForValue().setIfAbsent(key, value);
    }

    private String hashGet(String key, String field) {
        var memory = runtimeStore.current();
        if (memory != null) return memory.hashGet(key, field);
        Object value = redis.opsForHash().get(key, field);
        return value == null ? null : value.toString();
    }

    private Map<String, String> hashEntries(String key) {
        var memory = runtimeStore.current();
        if (memory != null) return memory.hashEntries(key);
        return redis.<String, String>opsForHash().entries(key);
    }

    private List<String> hashValues(String key) {
        var memory = runtimeStore.current();
        if (memory != null) return memory.hashValues(key);
        return redis.<String, String>opsForHash().values(key);
    }

    private void hashPut(String key, String field, String value) {
        var memory = runtimeStore.current();
        if (memory != null) memory.hashPut(key, field, value); else redis.opsForHash().put(key, field, value);
    }

    private void hashDelete(String key, String field) {
        var memory = runtimeStore.current();
        if (memory != null) memory.hashDelete(key, field); else redis.opsForHash().delete(key, field);
    }

    private void sortedAdd(String key, String value, double score) {
        var memory = runtimeStore.current();
        if (memory != null) memory.sortedSetAdd(key, value, score); else redis.opsForZSet().add(key, value, score);
    }

    private void sortedRemove(String key, String value) {
        var memory = runtimeStore.current();
        if (memory != null) memory.sortedSetRemove(key, value); else redis.opsForZSet().remove(key, value);
    }

    private Set<String> sortedRange(String key, double minimum, double maximum, boolean reverse) {
        var memory = runtimeStore.current();
        return memory != null
                ? memory.sortedSetRangeByScore(key, minimum, maximum, reverse)
                : reverse ? redis.opsForZSet().reverseRangeByScore(key, minimum, maximum)
                        : redis.opsForZSet().rangeByScore(key, minimum, maximum);
    }

    private void sortedRemoveByScore(String key, double minimum, double maximum) {
        var memory = runtimeStore.current();
        if (memory != null) memory.sortedSetRemoveByScore(key, minimum, maximum);
        else redis.opsForZSet().removeRangeByScore(key, minimum, maximum);
    }

    private void delete(String key) {
        var memory = runtimeStore.current();
        if (memory != null) memory.delete(key); else redis.delete(key);
    }

    private void rename(String source, String target) {
        var memory = runtimeStore.current();
        if (memory != null) memory.rename(source, target); else redis.rename(source, target);
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
