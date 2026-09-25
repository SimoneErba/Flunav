package com.flunav.backend.services;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.repositories.support.RedisKeyNamespace;
import com.flunav.backend.repositories.support.MultiSimulationRuntimeStore;
import com.flunav.backend.services.routing.MappingValueNormalizer;
import flunav.events.DestinationMappingRecord;
import flunav.events.MapDestinationsEvent;
import flunav.types.DataType;
import flunav.types.OperatorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stores and evaluates rules that translate item fields into logical destinations.
 *
 * This is the first mapping stage. {@link DestinationExitMappingService} performs
 * the second stage from logical destinations to physical exit locations. Rules are
 * evaluated against domain time and the active Redis namespace, so the same event
 * produces the same result in live processing, replay, and isolated simulation.
 */
@Service
public class DestinationMappingService {
    private static final Logger logger = LoggerFactory.getLogger(DestinationMappingService.class);
    private static final String MAP_PREFIX = "destination_map:";
    private static final String FIELD_INDEX = "destination_map:fields";
    private static final String TABLE_KEY = "destination_map:records";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MultiSimulationRuntimeStore runtimeStore;

    public DestinationMappingService(StringRedisTemplate redis, ObjectMapper objectMapper,
            MultiSimulationRuntimeStore runtimeStore) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.runtimeStore = runtimeStore;
    }

    /**
     * Replaces the active destination-mapping table from a domain event.
     * The complete table is stored as one Redis value so routing sees a consistent
     * mapping set, and the TTL follows the latest validity window in the event.
     */
    public void saveMapDestinations(MapDestinationsEvent event) {
        List<DestinationMappingRecord> mappings = normalizeAndValidate(event);
        Instant updatedAt = Instant.now();
        String tableKey = tableKey();

        clearDestinationMappings();

        if (mappings.isEmpty()) {
            logger.info("Cleared destination mappings");
            return;
        }

        List<DestinationMappingValue> values = mappings.stream()
                .map(record -> new DestinationMappingValue(
                        record.getFieldName().trim(),
                        record.getDataType(),
                        record.getOperator(),
                        record.getValue().trim(),
                        record.getSecondOperator(),
                        trimToNull(record.getSecondValue()),
                        record.getDestinations(),
                        record.getValidFrom(),
                        record.getRushAt(),
                        record.getValidTo(),
                        event.getEventId(),
                        updatedAt))
                .toList();

        Instant maxValidTo = values.stream()
                .map(DestinationMappingValue::validTo)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);

        try {
            String json = objectMapper.writeValueAsString(values);
            var memory = runtimeStore.current();
            if (memory != null) memory.setValue(tableKey, json); else redis.opsForValue().set(tableKey, json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize destination mappings", e);
        }

        var memory = runtimeStore.current();
        if (memory != null) memory.sortedSetAdd(indexKey(), "records", maxValidTo.toEpochMilli());
        else redis.opsForZSet().add(indexKey(), "records", maxValidTo.toEpochMilli());
        long ttlSeconds = Duration.between(Instant.now(), maxValidTo).getSeconds();
        if (memory == null && ttlSeconds > 0) {
            redis.expire(tableKey, Duration.ofSeconds(ttlSeconds));
        }

        logger.info("Stored destination mappings count={} ttlSeconds={}", values.size(), Math.max(ttlSeconds, 0));
    }

    /**
     * Returns the stored mapping records in event DTO form.
     * Controllers use this view to expose configuration without leaking the Redis
     * persistence wrapper fields.
     */
    public List<DestinationMappingRecord> getDestinationMappings() {
        return getStoredMappings().stream()
                .map(value -> new DestinationMappingRecord(
                        value.fieldName(),
                        value.dataType(),
                        value.operator(),
                        value.value(),
                        value.secondOperator(),
                        value.secondValue(),
                        value.destinations(),
                        value.validFrom(),
                        value.rushAt(),
                        value.validTo()))
                .toList();
    }

    /**
     * Resolves item fields into ordered logical destinations.
     * Mappings are evaluated at the supplied domain time so live, replay, and
     * simulation routing all use the same validity-window semantics.
     */
    public List<String> resolveDestinations(Map<String, Object> rootFields, Map<String, Object> properties, Instant now) {
        if ((rootFields == null || rootFields.isEmpty()) && (properties == null || properties.isEmpty())) {
            return List.of();
        }

        Instant effectiveNow = (now != null) ? now : Instant.now();
        List<DestinationMappingValue> mappings = getStoredMappings();
        if (mappings.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> resolvedDestinations = new LinkedHashSet<>();
        for (DestinationMappingValue mapping : mappings) {
            if (effectiveNow.isBefore(mapping.validFrom()) || effectiveNow.isAfter(mapping.validTo())
                    || !applies(rootFields, properties, mapping)) {
                continue;
            }

            resolvedDestinations.addAll(mapping.destinations());
        }

        return List.copyOf(resolvedDestinations);
    }

    /**
     * Resolves destinations from custom properties only.
     * This overload is used when no first-class root fields are available.
     */
    public List<String> resolveDestinations(Map<String, Object> properties, Instant now) {
        return resolveDestinations(Map.of(), properties, now);
    }

    /**
     * Resolves destinations from a single field/value pair.
     * Ingestion paths use this compact overload when a scanner or external system
     * provides one routing attribute.
     */
    public List<String> resolveDestinations(String fieldName, String fieldValue, Instant now) {
        if (isBlank(fieldName) || isBlank(fieldValue)) {
            return List.of();
        }

        return resolveDestinations(Map.of(fieldName.trim(), fieldValue.trim()), now);
    }

    /**
     * Computes the routing priority at a domain timestamp without changing the
     * item's durable base priority.
     */
    public RushPriority evaluateRush(com.flunav.backend.domain.Item item, Instant decisionTime) {
        if (item == null) {
            return new RushPriority(0.0, false);
        }
        return evaluateRush(itemRootFields(item), item.getProperties(), item.getDestinations(), item.getPriority(),
                decisionTime);
    }

    public RushPriority evaluateRush(Map<String, Object> rootFields, Map<String, Object> properties,
            List<String> destinations, Double basePriority, Instant decisionTime) {
        double priority = basePriority == null ? 0.0 : basePriority;
        if (destinations == null || destinations.isEmpty()) {
            return new RushPriority(priority, false);
        }

        Instant effectiveTime = decisionTime != null ? decisionTime : Instant.now();
        Set<String> assignedDestinations = new HashSet<>(destinations);
        boolean active = getStoredMappings().stream()
                .filter(mapping -> mapping.rushAt() != null)
                .filter(mapping -> !effectiveTime.isBefore(mapping.rushAt())
                        && !effectiveTime.isAfter(mapping.validTo()))
                .filter(mapping -> mapping.destinations().stream().anyMatch(assignedDestinations::contains))
                .anyMatch(mapping -> applies(rootFields, properties, mapping));
        return new RushPriority(active ? 1.0 : priority, active);
    }

    /**
     * Normalizes mapping records before they are persisted in Redis.
     * Defaults from the legacy event shape are applied here, and duplicate logical
     * rows are rejected so replaying a mapping event stays deterministic.
     */
    private List<DestinationMappingRecord> normalizeAndValidate(MapDestinationsEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("MapDestinationsEvent is required");
        }
        if (event.getMappings() == null) {
            throw new IllegalArgumentException("mappings are required");
        }
        Set<String> logicalRows = new HashSet<>();
        List<DestinationMappingRecord> normalized = new ArrayList<>();
        for (DestinationMappingRecord record : event.getMappings()) {
            if (record == null) {
                throw new IllegalArgumentException("mapping record is required");
            }
            String fieldName = !isBlank(record.getFieldName()) ? record.getFieldName() : event.getFieldName();
            DataType dataType = record.getDataType() != null ? record.getDataType() : DataType.STRING;
            OperatorType operator = record.getOperator() != null ? record.getOperator() : OperatorType.EQUAL;
            if (isBlank(fieldName)) {
                throw new IllegalArgumentException("mapping fieldName is required");
            }
            if (isBlank(record.getValue())) {
                throw new IllegalArgumentException("mapping value is required");
            }
            List<String> destinations = MappingValueNormalizer.requiredOrderedValues(
                    record.getDestinations(), "mapping destinations");
            if (record.getValidFrom() == null || record.getValidTo() == null) {
                throw new IllegalArgumentException("mapping validFrom and validTo are required");
            }
            if (!record.getValidTo().isAfter(record.getValidFrom())) {
                throw new IllegalArgumentException("mapping validTo must be after validFrom");
            }
            if (record.getRushAt() != null
                    && (record.getRushAt().isBefore(record.getValidFrom())
                            || !record.getRushAt().isBefore(record.getValidTo()))) {
                throw new IllegalArgumentException("mapping rushAt must be on or after validFrom and before validTo");
            }
            validateOperator(dataType, operator);
            validateComparableValue(dataType, record.getValue());
            DisplayRulesService.validateRange(dataType, operator, record.getSecondOperator(), record.getSecondValue());
            if (record.getSecondValue() != null) {
                validateComparableValue(dataType, record.getSecondValue());
            }

            String logicalRow = String.join("|",
                    fieldName.trim(),
                    dataType.name(),
                    operator.name(),
                    record.getValue().trim(),
                    record.getSecondOperator() == null ? "" : record.getSecondOperator().name(),
                    trimToNull(record.getSecondValue()) == null ? "" : trimToNull(record.getSecondValue()),
                    record.getValidFrom().toString(),
                    record.getRushAt() == null ? "" : record.getRushAt().toString(),
                    record.getValidTo().toString());
            if (!logicalRows.add(logicalRow)) {
                throw new IllegalArgumentException("duplicate destination mapping row");
            }
            normalized.add(new DestinationMappingRecord(
                    fieldName.trim(),
                    dataType,
                    operator,
                    record.getValue().trim(),
                    record.getSecondOperator(),
                    trimToNull(record.getSecondValue()),
                    destinations,
                    record.getValidFrom(),
                    record.getRushAt(),
                    record.getValidTo()));
        }
        return normalized;
    }

    /**
     * Loads the current mapping table from the active Redis namespace.
     * A parse failure returns an empty table rather than routing against a partially
     * corrupted mapping payload.
     */
    private List<DestinationMappingValue> getStoredMappings() {
        var memory = runtimeStore.current();
        String json = memory != null ? memory.getValue(tableKey()) : redis.opsForValue().get(tableKey());
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<DestinationMappingValue>>() {
            });
        } catch (Exception e) {
            logger.warn("Failed to parse destination mappings", e);
            return List.of();
        }
    }

    /**
     * Clears all destination-mapping keys in the current context.
     * This removes both the current table and legacy per-field keys so old data
     * cannot influence future route resolution.
     */
    private void clearDestinationMappings() {
        var memory = runtimeStore.current();
        if (memory != null) {
            memory.deleteMatching(namespaced(MAP_PREFIX));
            memory.delete(tableKey(), indexKey());
            return;
        }
        Set<String> mapKeys = redis.keys(namespaced(MAP_PREFIX + "*"));
        if (mapKeys != null && !mapKeys.isEmpty()) {
            redis.delete(mapKeys);
        }
        redis.delete(List.of(tableKey(), indexKey()));
    }

    /**
     * Evaluates both conditions for one destination-mapping row.
     * The same rule evaluator is used by display rules so typed comparisons and
     * root-field precedence stay consistent across mapping and styling logic.
     */
    private boolean applies(Map<String, Object> rootFields, Map<String, Object> properties,
            DestinationMappingValue mapping) {
        boolean first = RuleActivationEvaluator.isActive(rootFields, properties, mapping.fieldName(),
                mapping.dataType(), mapping.operator(), mapping.value());
        return first && (mapping.secondOperator() == null
                || RuleActivationEvaluator.isActive(rootFields, properties, mapping.fieldName(),
                        mapping.dataType(), mapping.secondOperator(), mapping.secondValue()));
    }

    private Map<String, Object> itemRootFields(com.flunav.backend.domain.Item item) {
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("id", item.getId());
        fields.put("name", item.getName());
        fields.put("active", item.isActive());
        fields.put("priority", item.getPriority());
        fields.put("positionId", item.getPositionId());
        fields.put("locationId", item.getPositionId());
        fields.put("positionType", item.getPositionType());
        fields.put("entryTimestamp", item.getEntryTimestamp());
        fields.put("destinations", item.getDestinations());
        fields.put("selectedExitId", item.getSelectedExitId());
        fields.put("routingStatus", item.getRoutingStatus());
        return fields;
    }

    /**
     * Restricts mapping operators to combinations the evaluator can compare safely.
     * Strings and booleans support equality only because ordering serialized values
     * would make route resolution ambiguous.
     */
    private void validateOperator(DataType dataType, OperatorType operator) {
        if ((dataType == DataType.STRING || dataType == DataType.BOOLEAN) && operator != OperatorType.EQUAL) {
            throw new IllegalArgumentException("operator must be EQUAL for " + dataType);
        }
    }

    /**
     * Parses a mapping value using the same type rules used at resolution time.
     * Invalid values are rejected before the mapping table can affect routing.
     */
    private void validateComparableValue(DataType dataType, String value) {
        try {
            switch (dataType) {
                case NUMBER -> {
                    double number = Double.parseDouble(value);
                    if (!Double.isFinite(number)) {
                        throw new IllegalArgumentException("numeric mapping value must be finite");
                    }
                }
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException("boolean mapping value must be true or false");
                    }
                }
                case DATETIME -> Instant.parse(value);
                case STRING -> {
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("mapping value is invalid for " + dataType, e);
        }
    }

    /**
     * Returns the Redis key for the current mapping table.
     * The key is namespaced so simulations can override mapping behavior safely.
     */
    private String tableKey() {
        return namespaced(TABLE_KEY);
    }

    /**
     * Returns the Redis key for mapping validity metadata.
     * The index follows the same namespace as the table it describes.
     */
    private String indexKey() {
        return namespaced(FIELD_INDEX);
    }

    /**
     * Builds mapping keys from the active simulation context.
     * Simulation-specific destination maps must not leak into live routing or other
     * simulations because they affect future route assignment.
     */
    private String namespaced(String key) {
        return RedisKeyNamespace.current(key);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    public record DestinationMappingValue(
            String fieldName,
            DataType dataType,
            OperatorType operator,
            String value,
            OperatorType secondOperator,
            String secondValue,
            List<String> destinations,
            Instant validFrom,
            Instant rushAt,
            Instant validTo,
            String sourceEventId,
            Instant updatedAt) {
        @JsonCreator
        public DestinationMappingValue(
                @JsonProperty("fieldName") String fieldName,
                @JsonProperty("dataType") DataType dataType,
                @JsonProperty("operator") OperatorType operator,
                @JsonProperty("value") String value,
                @JsonProperty("secondOperator") OperatorType secondOperator,
                @JsonProperty("secondValue") String secondValue,
                @JsonProperty("destinations") List<String> destinations,
                @JsonProperty("validFrom") Instant validFrom,
                @JsonProperty("rushAt") Instant rushAt,
                @JsonProperty("validTo") Instant validTo,
                @JsonProperty("sourceEventId") String sourceEventId,
                @JsonProperty("updatedAt") Instant updatedAt) {
            this.fieldName = fieldName;
            this.dataType = dataType;
            this.operator = operator;
            this.value = value;
            this.secondOperator = secondOperator;
            this.secondValue = secondValue;
            this.destinations = destinations;
            this.validFrom = validFrom;
            this.rushAt = rushAt;
            this.validTo = validTo;
            this.sourceEventId = sourceEventId;
            this.updatedAt = updatedAt;
        }
    }

    public record RushPriority(double effectivePriority, boolean rushActive) {
    }
}
