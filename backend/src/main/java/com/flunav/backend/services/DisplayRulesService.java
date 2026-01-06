package com.flunav.backend.services;

import flunav.types.DataType;
import flunav.types.DisplayRule;
import flunav.types.OperatorType;

import org.springframework.stereotype.Service;

import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.OElement;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DisplayRulesService {

    private static final String DISPLAY_RULES_CLASS = "DisplayRules";
    private static final String RULES_PROPERTY = "rules";

    private final OrientDBService orientDBService;

    public DisplayRulesService(OrientDBService orientDBService) {
        this.orientDBService = orientDBService;
    }

    public boolean applies(Map<String, Object> properties, DisplayRule rule) {
        Object propValue = properties.get(rule.getFieldName());
        if (propValue == null || rule.getOperator() == null) {
            return false;
        }

        Object ruleValue = rule.getValue();

        try {
            switch (rule.getDataType()) {
                case STRING:
                    return propValue.toString().equals(ruleValue.toString());

                case BOOLEAN:
                    return Boolean.parseBoolean(propValue.toString()) == Boolean.parseBoolean(ruleValue.toString());

                case NUMBER:
                    double propNum = Double.parseDouble(propValue.toString());
                    double ruleNum = Double.parseDouble(ruleValue.toString());

                    return switch (rule.getOperator()) {
                        case EQUAL -> propNum == ruleNum;
                        case GREATER -> propNum > ruleNum;
                        case LESSER -> propNum < ruleNum;
                    };

                case DATETIME:
                    Instant propTime = Instant.parse(propValue.toString());
                    Instant ruleTime = Instant.parse(ruleValue.toString());

                    return switch (rule.getOperator()) {
                        case EQUAL -> propTime.equals(ruleTime);
                        case GREATER -> propTime.isAfter(ruleTime);
                        case LESSER -> propTime.isBefore(ruleTime);
                    };

                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
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
        try (ODatabaseSession session = orientDBService.getSession()) {
            OResultSet rs = session.query("SELECT FROM " + DISPLAY_RULES_CLASS);
            if (rs.hasNext()) {
                OResult result = rs.next();
                OElement element = result.getElement().orElse(null);
                if (element != null) {
                    List<ODocument> ruleDocs = element.getProperty(RULES_PROPERTY);
                    return ruleDocs.stream().map(this::toDisplayRule).collect(Collectors.toList());
                }
            }
            return List.of();
        }
    }

    public void updateDisplayRules(List<DisplayRule> rules) {
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
