package com.flunav.backend.services;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.context.DatabaseContextHolder;
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

@Service
public class DestinationMappingService {
    private static final Logger logger = LoggerFactory.getLogger(DestinationMappingService.class);
    private static final String MAP_PREFIX = "destination_map:";
    private static final String FIELD_INDEX = "destination_map:fields";
    private static final String TABLE_KEY = "destination_map:records";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DestinationMappingService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

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
                        record.getValidTo(),
                        event.getEventId(),
                        updatedAt))
                .toList();

        Instant maxValidTo = values.stream()
                .map(DestinationMappingValue::validTo)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);

        try {
            redis.opsForValue().set(tableKey, objectMapper.writeValueAsString(values));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize destination mappings", e);
        }

        redis.opsForZSet().add(indexKey(), "records", maxValidTo.toEpochMilli());
        long ttlSeconds = Duration.between(Instant.now(), maxValidTo).getSeconds();
        if (ttlSeconds > 0) {
            redis.expire(tableKey, Duration.ofSeconds(ttlSeconds));
        }

        logger.info("Stored destination mappings count={} ttlSeconds={}", values.size(), Math.max(ttlSeconds, 0));
    }

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
                        value.validTo()))
                .toList();
    }

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

    public List<String> resolveDestinations(Map<String, Object> properties, Instant now) {
        return resolveDestinations(Map.of(), properties, now);
    }

    public List<String> resolveDestinations(String fieldName, String fieldValue, Instant now) {
        if (isBlank(fieldName) || isBlank(fieldValue)) {
            return List.of();
        }

        return resolveDestinations(Map.of(fieldName.trim(), fieldValue.trim()), now);
    }

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
            List<String> destinations = normalizeOrderedValues(record.getDestinations(), "mapping destinations");
            if (record.getValidFrom() == null || record.getValidTo() == null) {
                throw new IllegalArgumentException("mapping validFrom and validTo are required");
            }
            if (!record.getValidTo().isAfter(record.getValidFrom())) {
                throw new IllegalArgumentException("mapping validTo must be after validFrom");
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
                    record.getValidTo()));
        }
        return normalized;
    }

    private List<DestinationMappingValue> getStoredMappings() {
        String json = redis.opsForValue().get(tableKey());
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

    private void clearDestinationMappings() {
        Set<String> mapKeys = redis.keys(namespaced(MAP_PREFIX + "*"));
        if (mapKeys != null && !mapKeys.isEmpty()) {
            redis.delete(mapKeys);
        }
        redis.delete(List.of(tableKey(), indexKey()));
    }

    private boolean applies(Map<String, Object> rootFields, Map<String, Object> properties,
            DestinationMappingValue mapping) {
        boolean first = RuleActivationEvaluator.isActive(rootFields, properties, mapping.fieldName(),
                mapping.dataType(), mapping.operator(), mapping.value());
        return first && (mapping.secondOperator() == null
                || RuleActivationEvaluator.isActive(rootFields, properties, mapping.fieldName(),
                        mapping.dataType(), mapping.secondOperator(), mapping.secondValue()));
    }

    private void validateOperator(DataType dataType, OperatorType operator) {
        if ((dataType == DataType.STRING || dataType == DataType.BOOLEAN) && operator != OperatorType.EQUAL) {
            throw new IllegalArgumentException("operator must be EQUAL for " + dataType);
        }
    }

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

    private String tableKey() {
        return namespaced(TABLE_KEY);
    }

    private String indexKey() {
        return namespaced(FIELD_INDEX);
    }

    private String namespaced(String key) {
        String simId = DatabaseContextHolder.getSimulationId();
        return (simId != null) ? "sim:" + simId + ":" + key : key;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private List<String> normalizeOrderedValues(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " are required");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (isBlank(value)) {
                throw new IllegalArgumentException(fieldName + " must contain nonblank values");
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
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
            this.validTo = validTo;
            this.sourceEventId = sourceEventId;
            this.updatedAt = updatedAt;
        }
    }
}
