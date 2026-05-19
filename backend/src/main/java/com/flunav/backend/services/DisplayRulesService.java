package com.flunav.backend.services;

import flunav.types.DataType;
import flunav.types.DisplayRule;
import flunav.types.OperatorType;
import com.flunav.backend.context.DatabaseContextHolder;
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

    public DisplayRulesService(OrientDBService orientDBService, StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.orientDBService = orientDBService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String getNamespacedKey() {
        String simId = DatabaseContextHolder.getSimulationId();
        if (simId != null) {
            return "sim:" + simId + ":" + REDIS_KEY_PREFIX;
        }
        return REDIS_KEY_PREFIX;
    }

    public boolean applies(Map<String, Object> properties, DisplayRule rule) {
        if (rule == null) {
            return false;
        }
        return RuleActivationEvaluator.isActive(properties, rule.getFieldName(), rule.getDataType(),
                rule.getOperator(), rule.getValue());
    }

    public String applyDisplayRules(
            Map<String, Object> properties,
            List<DisplayRule> rules) {

        if (properties == null)
            return null;
        return rules.stream()
                .sorted(Comparator.comparingInt(DisplayRule::getPriority))
                .filter(rule -> applies(properties, rule))
                .map(DisplayRule::getColor)
                .findFirst()
                .orElse(null);
    }

    public List<DisplayRule> getDisplayRules() {
        // 1. Try Cache
        String key = getNamespacedKey();
        String cachedJson = redisTemplate.opsForValue().get(key);
        if (cachedJson != null) {
            try {
                return objectMapper.readValue(cachedJson, new TypeReference<List<DisplayRule>>() {
                });
            } catch (JsonProcessingException e) {
                logger.warn("Failed to parse display rules from cache", e);
            }
        }

        // 2. Fetch from DB
        try (ODatabaseSession session = orientDBService.getSession()) {
            List<DisplayRule> rules = new ArrayList<>();
            if (session == null)
                return rules;
            OResultSet rs = session.query("SELECT FROM " + DISPLAY_RULES_CLASS);
            if (rs == null)
                return rules;
            if (rs.hasNext()) {
                OResult result = rs.next();
                OElement element = result.getElement().orElse(null);
                if (element != null) {
                    List<ODocument> ruleDocs = element.getProperty(RULES_PROPERTY);
                    rules = ruleDocs.stream().map(this::toDisplayRule).collect(Collectors.toList());
                }
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

    public void updateDisplayRules(List<DisplayRule> rules) {
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

    private ODocument toDocument(DisplayRule rule) {
        ODocument doc = new ODocument();
        doc.setProperty("fieldName", rule.getFieldName());
        doc.setProperty("dataType", rule.getDataType());
        doc.setProperty("operator", rule.getOperator());
        doc.setProperty("value", rule.getValue());
        doc.setProperty("color", rule.getColor());
        doc.setProperty("priority", rule.getPriority());
        return doc;
    }

    private DisplayRule toDisplayRule(ODocument doc) {
        DisplayRule rule = new DisplayRule();
        rule.setFieldName(doc.getProperty("fieldName"));

        String typeStr = doc.getProperty("dataType");
        rule.setDataType(typeStr == null ? DataType.STRING : DataType.fromString(typeStr));

        String opStr = doc.getProperty("operator");
        rule.setOperator(typeStr == null ? OperatorType.EQUAL : OperatorType.fromString(opStr));
        rule.setValue(doc.getProperty("value"));
        rule.setColor(doc.getProperty("color"));
        rule.setPriority(doc.getProperty("priority"));
        return rule;
    }

}
