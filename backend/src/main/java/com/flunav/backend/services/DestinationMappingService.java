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
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DestinationMappingService {
    private static final Logger logger = LoggerFactory.getLogger(DestinationMappingService.class);
    private static final String MAP_PREFIX = "destination_map:";
    private static final String FIELD_INDEX = "destination_map:fields";
    private static final String TABLE_KEY = "destination_map:records";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LocationService locationService;

    public DestinationMappingService(StringRedisTemplate redis, ObjectMapper objectMapper, LocationService locationService) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.locationService = locationService;
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
                        record.getDestination().trim(),
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
                        value.destination(),
                        value.validFrom(),
                        value.validTo()))
                .toList();
    }

    public Optional<String> resolveDestination(Map<String, Object> properties, Instant now) {
        if (properties == null || properties.isEmpty()) {
            return Optional.empty();
        }

        Instant effectiveNow = (now != null) ? now : Instant.now();
        List<DestinationMappingValue> mappings = getStoredMappings();
        if (mappings.isEmpty()) {
            return Optional.empty();
        }

        String resolvedDestination = null;
        for (DestinationMappingValue mapping : mappings) {
            Object rawValue = findProperty(properties, mapping.fieldName());
            if (rawValue == null) {
                continue;
            }

            if (effectiveNow.isBefore(mapping.validFrom()) || effectiveNow.isAfter(mapping.validTo())
                    || !applies(rawValue, mapping)) {
                continue;
            }

            if (resolvedDestination != null && !resolvedDestination.equals(mapping.destination())) {
                logger.warn("Multiple destination mappings matched different destinations for fields in item properties");
                return Optional.empty();
            }
            resolvedDestination = mapping.destination();
        }

        return Optional.ofNullable(resolvedDestination);
    }

    public Optional<String> resolveDestination(String fieldName, String fieldValue, Instant now) {
        if (isBlank(fieldName) || isBlank(fieldValue)) {
            return Optional.empty();
        }

        return resolveDestination(Map.of(fieldName.trim(), fieldValue.trim()), now);
    }

    private List<DestinationMappingRecord> normalizeAndValidate(MapDestinationsEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("MapDestinationsEvent is required");
        }
        if (event.getMappings() == null) {
            throw new IllegalArgumentException("mappings are required");
        }
        Set<String> destinationIds = locationService.getAllLocations().stream()
                .map(location -> location.getId())
                .collect(Collectors.toSet());
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
            if (isBlank(record.getDestination())) {
                throw new IllegalArgumentException("mapping destination is required");
            }
            if (!destinationIds.contains(record.getDestination().trim())) {
                throw new IllegalArgumentException("unknown destination location: " + record.getDestination());
            }
            if (record.getValidFrom() == null || record.getValidTo() == null) {
                throw new IllegalArgumentException("mapping validFrom and validTo are required");
            }
            if (!record.getValidTo().isAfter(record.getValidFrom())) {
                throw new IllegalArgumentException("mapping validTo must be after validFrom");
            }
            validateOperator(dataType, operator);
            validateComparableValue(dataType, record.getValue());

            String logicalRow = String.join("|",
                    fieldName.trim(),
                    dataType.name(),
                    operator.name(),
                    record.getValue().trim(),
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
                    record.getDestination().trim(),
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

    private boolean applies(Object rawValue, DestinationMappingValue mapping) {
        try {
            return switch (mapping.dataType()) {
                case STRING -> rawValue.toString().equals(mapping.value());
                case BOOLEAN -> Boolean.parseBoolean(rawValue.toString()) == Boolean.parseBoolean(mapping.value());
                case NUMBER -> compareNumbers(rawValue, mapping);
                case DATETIME -> compareInstants(rawValue, mapping);
            };
        } catch (Exception e) {
            return false;
        }
    }

    private boolean compareNumbers(Object rawValue, DestinationMappingValue mapping) {
        double propNum = Double.parseDouble(rawValue.toString());
        double ruleNum = Double.parseDouble(mapping.value());
        return switch (mapping.operator()) {
            case EQUAL -> propNum == ruleNum;
            case GREATER -> propNum > ruleNum;
            case LESSER -> propNum < ruleNum;
        };
    }

    private boolean compareInstants(Object rawValue, DestinationMappingValue mapping) {
        Instant propTime = Instant.parse(rawValue.toString());
        Instant ruleTime = Instant.parse(mapping.value());
        return switch (mapping.operator()) {
            case EQUAL -> propTime.equals(ruleTime);
            case GREATER -> propTime.isAfter(ruleTime);
            case LESSER -> propTime.isBefore(ruleTime);
        };
    }

    private Object findProperty(Map<String, Object> properties, String fieldName) {
        return properties.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(fieldName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private void validateOperator(DataType dataType, OperatorType operator) {
        if ((dataType == DataType.STRING || dataType == DataType.BOOLEAN) && operator != OperatorType.EQUAL) {
            throw new IllegalArgumentException("operator must be EQUAL for " + dataType);
        }
    }

    private void validateComparableValue(DataType dataType, String value) {
        try {
            switch (dataType) {
                case NUMBER -> Double.parseDouble(value);
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

    public record DestinationMappingValue(
            String fieldName,
            DataType dataType,
            OperatorType operator,
            String value,
            String destination,
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
                @JsonProperty("destination") String destination,
                @JsonProperty("validFrom") Instant validFrom,
                @JsonProperty("validTo") Instant validTo,
                @JsonProperty("sourceEventId") String sourceEventId,
                @JsonProperty("updatedAt") Instant updatedAt) {
            this.fieldName = fieldName;
            this.dataType = dataType;
            this.operator = operator;
            this.value = value;
            this.destination = destination;
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.sourceEventId = sourceEventId;
            this.updatedAt = updatedAt;
        }
    }
}
