package com.flunav.backend.services;

import flunav.types.DataType;
import flunav.types.DisplayRule;
import flunav.types.OperatorType;
import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.response.DisplayRuleVisualStyle;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DisplayRulesService {
    private static final Logger logger = LoggerFactory.getLogger(DisplayRulesService.class);

    private static final String DISPLAY_RULES_CLASS = "DisplayRules";
    private static final String RULES_PROPERTY = "rules";
    private static final String REDIS_KEY_PREFIX = "display_rules";

    private final OrientDBService orientDBService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.flunav.backend.repositories.support.MultiSimulationRuntimeStore runtimeStore;

    public DisplayRulesService(OrientDBService orientDBService, StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper, com.flunav.backend.repositories.support.MultiSimulationRuntimeStore runtimeStore) {
        this.runtimeStore = runtimeStore;
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the display-rule cache key for the current live or simulation context.
     * Simulation-specific display rules must not affect the live graph view.
     */
    private String getNamespacedKey() {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return "sim:" + simId + ":" + REDIS_KEY_PREFIX;
        }
        return REDIS_KEY_PREFIX;
    }

    /**
     * Evaluates one display rule against typed root fields first and custom
     * properties second. Root fields win so top-level item, location, and conveyor
     * attributes cannot be shadowed by similarly named custom properties.
     */
    public boolean applies(Map<String, Object> rootFields, Map<String, Object> properties, DisplayRule rule) {
        if (rule == null) {
            return false;
        }
        boolean first = RuleActivationEvaluator.isActive(rootFields, properties, rule.getFieldName(),
                rule.getDataType(), rule.getOperator(), rule.getValue());
        return first && (rule.getSecondOperator() == null
                || RuleActivationEvaluator.isActive(rootFields, properties, rule.getFieldName(),
                        rule.getDataType(), rule.getSecondOperator(), rule.getSecondValue()));
    }

    /**
     * Resolves the visual style produced by the ordered display rules.
     * Fill and border can come from different matching rules, so each visual slot
     * is filled once and higher-priority matches keep their result.
     */
    public DisplayRuleVisualStyle applyDisplayRules(
            Map<String, Object> rootFields,
            Map<String, Object> properties,
            List<DisplayRule> rules) {

        if (rules == null || rules.isEmpty()) {
            return null;
        }

        String fillColor = null;
        String borderColor = null;
        Double borderWidth = null;
        for (DisplayRule rule : rules.stream()
                .sorted(Comparator.comparingInt(rule -> rule.getPriority() != null ? rule.getPriority() : 0))
                .toList()) {
            if (!applies(rootFields, properties, rule)) {
                continue;
            }
            if (fillColor == null && rule.getColor() != null && !rule.getColor().isBlank()) {
                fillColor = rule.getColor();
            }
            if (borderColor == null && borderWidth == null
                    && rule.getBorderColor() != null && !rule.getBorderColor().isBlank()
                    && rule.getBorderWidth() != null) {
                borderColor = rule.getBorderColor();
                borderWidth = rule.getBorderWidth();
            }
            if (fillColor != null && borderColor != null) {
                break;
            }
        }
        return fillColor == null && borderColor == null
                ? null
                : new DisplayRuleVisualStyle(fillColor, borderColor, borderWidth);
    }

    /**
     * Applies display rules to custom properties only.
     * This overload is kept for callers that do not have separate root fields.
     */
    public DisplayRuleVisualStyle applyDisplayRules(Map<String, Object> properties, List<DisplayRule> rules) {
        return applyDisplayRules(Map.of(), properties, rules);
    }

    /**
     * Loads display rules through a simulation-aware write-through cache.
     * OrientDB is the durable source, Redis accelerates graph reads, and default
     * priority rules preserve visible priority styling until a user saves rules.
     */
    public List<DisplayRule> getDisplayRules() {
        // 1. Try Cache
        String key = getNamespacedKey();
        var memory = runtimeStore.current();
        String cachedJson = memory == null ? redisTemplate.opsForValue().get(key) : memory.getValue(key);
        if (cachedJson != null) {
            try {
                return objectMapper.readValue(cachedJson, new TypeReference<List<DisplayRule>>() {
                });
            } catch (JsonProcessingException e) {
                logger.warn("Failed to parse display rules from cache", e);
            }
        }

        if (memory != null) return defaultPriorityRules();

        // 2. Fetch from DB
        try (ODatabaseSession session = orientDBService.getSession()) {
            List<DisplayRule> rules = new ArrayList<>();
            if (session == null)
                return rules;
            OResultSet rs = session.query("SELECT FROM " + DISPLAY_RULES_CLASS);
            if (rs == null)
                return rules;
            boolean hasSavedRules = rs.hasNext();
            if (hasSavedRules) {
                OResult result = rs.next();
                OElement element = result.getElement().orElse(null);
                if (element != null) {
                    List<ODocument> ruleDocs = element.getProperty(RULES_PROPERTY);
                    rules = ruleDocs.stream().map(this::toDisplayRule).collect(Collectors.toList());
                }
            }
            if (!hasSavedRules) {
                rules = defaultPriorityRules();
            }

            // 3. Update Cache
            try {
                String json = objectMapper.writeValueAsString(rules);
                redisTemplate.opsForValue().set(key, json);
            } catch (JsonProcessingException e) {
                logger.warn("Failed to serialize display rules for cache", e);
            }

            return rules;
        }
    }

    /**
     * Replaces the complete display-rule set in OrientDB and Redis.
     * The rules are stored as one document so graph reads see a consistent ordered
     * set rather than a partially updated collection.
     */
    public void updateDisplayRules(List<DisplayRule> rules) {
        validateRules(rules);
        var memory = runtimeStore.current();
        if (memory != null) {
            try { memory.setValue(getNamespacedKey(), objectMapper.writeValueAsString(rules)); }
            catch (JsonProcessingException failure) { throw new IllegalArgumentException("Cannot serialize display rules", failure); }
            return;
        }
        // 1. Update DB
        orientDBService.withTransaction(session -> {
            // Delete existing rules
            session.command("DELETE FROM " + DISPLAY_RULES_CLASS);

            // Create new rules document
            ODocument doc = new ODocument(DISPLAY_RULES_CLASS);
            List<ODocument> ruleDocs = rules.stream().map(this::toDocument).collect(Collectors.toList());
            doc.setProperty(RULES_PROPERTY, ruleDocs,
                    com.orientechnologies.orient.core.metadata.schema.OType.EMBEDDEDLIST);
            session.save(doc);
        });

        // 2. Update Cache (Write-Through)
        try {
            String key = getNamespacedKey();
            String json = objectMapper.writeValueAsString(rules);
            redisTemplate.opsForValue().set(key, json);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to update display rules cache", e);
        }
    }

    /**
     * Converts a DTO rule into an embedded OrientDB document.
     * The field names are kept stable because saved rule documents are rehydrated
     * directly by toDisplayRule.
     */
    private ODocument toDocument(DisplayRule rule) {
        ODocument doc = new ODocument();
        doc.setProperty("fieldName", rule.getFieldName());
        doc.setProperty("dataType", rule.getDataType());
        doc.setProperty("operator", rule.getOperator());
        doc.setProperty("value", rule.getValue());
        doc.setProperty("secondOperator", rule.getSecondOperator());
        doc.setProperty("secondValue", rule.getSecondValue());
        doc.setProperty("color", rule.getColor());
        doc.setProperty("borderColor", rule.getBorderColor());
        doc.setProperty("borderWidth", rule.getBorderWidth());
        doc.setProperty("priority", rule.getPriority());
        return doc;
    }

    /**
     * Rehydrates an embedded OrientDB rule document into the shared rule DTO.
     * Missing historical fields fall back to the earliest supported semantics so
     * old saved rules remain usable after additive rule changes.
     */
    private DisplayRule toDisplayRule(ODocument doc) {
        DisplayRule rule = new DisplayRule();
        rule.setFieldName(doc.getProperty("fieldName"));

        String typeStr = doc.getProperty("dataType");
        rule.setDataType(typeStr == null ? DataType.STRING : DataType.fromString(typeStr));

        String opStr = doc.getProperty("operator");
        rule.setOperator(typeStr == null ? OperatorType.EQUAL : OperatorType.fromString(opStr));
        rule.setValue(doc.getProperty("value"));
        String secondOpStr = doc.getProperty("secondOperator");
        rule.setSecondOperator(secondOpStr == null ? null : OperatorType.fromString(secondOpStr));
        rule.setSecondValue(doc.getProperty("secondValue"));
        rule.setColor(doc.getProperty("color"));
        rule.setBorderColor(doc.getProperty("borderColor"));
        Number borderWidth = doc.getProperty("borderWidth");
        rule.setBorderWidth(borderWidth == null ? null : borderWidth.doubleValue());
        rule.setPriority(doc.getProperty("priority"));
        return rule;
    }

    /**
     * Validates user-supplied rules before they become graph-wide styling logic.
     * This rejects ranges, operators, and visual border values that the evaluator
     * cannot apply deterministically during graph projection.
     */
    public void validateRules(List<DisplayRule> rules) {
        if (rules == null) {
            throw new IllegalArgumentException("display rules are required");
        }
        for (DisplayRule rule : rules) {
            if (rule == null || rule.getFieldName() == null || rule.getFieldName().isBlank()
                    || rule.getDataType() == null || rule.getOperator() == null || rule.getValue() == null) {
                throw new IllegalArgumentException("display rule field, type, operator, and value are required");
            }
            validateRange(rule.getDataType(), rule.getOperator(), rule.getSecondOperator(), rule.getSecondValue());
            validateOperator(rule.getDataType(), rule.getOperator());
            validateComparableValue(rule.getDataType(), rule.getValue());
            if (rule.getSecondValue() != null) {
                validateComparableValue(rule.getDataType(), rule.getSecondValue());
            }
            if ((rule.getBorderColor() == null) != (rule.getBorderWidth() == null)) {
                throw new IllegalArgumentException("borderColor and borderWidth must be supplied together");
            }
            if (rule.getBorderWidth() != null
                    && (!Double.isFinite(rule.getBorderWidth()) || rule.getBorderWidth() < 0.0)) {
                throw new IllegalArgumentException("borderWidth must be finite and non-negative");
            }
        }
    }

    /**
     * Validates the optional second condition used for numeric and datetime ranges.
     * The two operators must bound opposite sides of the same field so the rule
     * represents a finite interval instead of two unrelated comparisons.
     */
    static void validateRange(DataType dataType, OperatorType firstOperator, OperatorType secondOperator,
            Object secondValue) {
        if (secondOperator == null && secondValue == null) {
            return;
        }
        if (secondOperator == null || secondValue == null) {
            throw new IllegalArgumentException("secondOperator and secondValue must be supplied together");
        }
        if (dataType != DataType.NUMBER && dataType != DataType.DATETIME) {
            throw new IllegalArgumentException("second conditions require NUMBER or DATETIME");
        }
        boolean firstLower = firstOperator == OperatorType.GREATER
                || firstOperator == OperatorType.GREATER_OR_EQUAL;
        boolean firstUpper = firstOperator == OperatorType.LESSER
                || firstOperator == OperatorType.LESSER_OR_EQUAL;
        boolean secondLower = secondOperator == OperatorType.GREATER
                || secondOperator == OperatorType.GREATER_OR_EQUAL;
        boolean secondUpper = secondOperator == OperatorType.LESSER
                || secondOperator == OperatorType.LESSER_OR_EQUAL;
        if (!(firstLower && secondUpper || firstUpper && secondLower)) {
            throw new IllegalArgumentException("range operators must use opposite directions");
        }
    }

    /**
     * Restricts operators to combinations the evaluator can compare safely.
     * Strings and booleans only support equality because ordering them would be
     * ambiguous across serialized values.
     */
    private void validateOperator(DataType dataType, OperatorType operator) {
        if ((dataType == DataType.STRING || dataType == DataType.BOOLEAN) && operator != OperatorType.EQUAL) {
            throw new IllegalArgumentException("operator must be EQUAL for " + dataType);
        }
    }

    /**
     * Parses rule values using the same type expectations used at evaluation time.
     * Invalid values are rejected on write so graph reads do not fail while applying
     * display rules to live or simulation data.
     */
    private void validateComparableValue(DataType dataType, Object value) {
        try {
            switch (dataType) {
                case NUMBER -> {
                    double number = Double.parseDouble(value.toString());
                    if (!Double.isFinite(number)) {
                        throw new IllegalArgumentException("numeric rule value must be finite");
                    }
                }
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(value.toString())
                            && !"false".equalsIgnoreCase(value.toString())) {
                        throw new IllegalArgumentException("boolean rule value must be true or false");
                    }
                }
                case DATETIME -> java.time.Instant.parse(value.toString());
                case STRING -> {
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("display rule value is invalid for " + dataType, e);
        }
    }

    /**
     * Supplies baseline priority styling for a new system.
     * These rules map the domain priority bands to border styling without requiring
     * a persisted display-rule document during initial setup.
     */
    private List<DisplayRule> defaultPriorityRules() {
        return List.of(
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.0,
                        OperatorType.LESSER, 0.4, null, "#3b82f6", 1.0, 1),
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.4,
                        OperatorType.LESSER, 0.8, null, "#f97316", 2.0, 2),
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.8,
                        OperatorType.LESSER_OR_EQUAL, 1.0, null, "#facc15", 3.0, 3));
    }
}
