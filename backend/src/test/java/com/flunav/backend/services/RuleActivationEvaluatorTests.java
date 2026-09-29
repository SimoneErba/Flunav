package com.flunav.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flunav.backend.models.response.DisplayRuleVisualStyle;

import flunav.types.DataType;
import flunav.types.DisplayRule;
import flunav.types.OperatorType;

class RuleActivationEvaluatorTests {

    @Test
    void rootFieldWinsOverPropertyCollisionAndInclusiveOperatorsCoverBoundaries() {
        Map<String, Object> root = Map.of("priority", 0.8);
        Map<String, Object> properties = Map.of("priority", 0.1);

        assertTrue(RuleActivationEvaluator.isActive(
                root, properties, "priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.8));
        assertTrue(RuleActivationEvaluator.isActive(
                root, properties, "priority", DataType.NUMBER, OperatorType.LESSER_OR_EQUAL, 0.8));
        assertFalse(RuleActivationEvaluator.isActive(
                root, properties, "priority", DataType.NUMBER, OperatorType.LESSER, 0.4));
    }

    @Test
    void nullableRootFieldShadowsCustomPropertyWithoutFailingEvaluation() {
        Map<String, Object> root = new HashMap<>();
        root.put("priority", null);
        Map<String, Object> properties = Map.of("priority", 0.8);

        assertFalse(RuleActivationEvaluator.isActive(
                root, properties, "priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.4));
    }

    @Test
    void firstMatchingFillAndFirstMatchingCompleteBorderResolveIndependently() {
        DisplayRulesService service = new DisplayRulesService(null, null, null, null);
        List<DisplayRule> rules = List.of(
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.0,
                        OperatorType.LESSER, 1.0, null, "#3b82f6", 1.0, 1),
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.0,
                        OperatorType.LESSER_OR_EQUAL, 1.0, "#ef4444", null, null, 2));

        DisplayRuleVisualStyle style = service.applyDisplayRules(Map.of("priority", 0.4), Map.of(), rules);

        assertEquals("#ef4444", style.getFillColor());
        assertEquals("#3b82f6", style.getBorderColor());
        assertEquals(1.0, style.getBorderWidth());
    }

    @Test
    void rangeValidationRequiresComparableValuesAndOppositeDirections() {
        assertThrows(IllegalArgumentException.class,
                () -> DisplayRulesService.validateRange(
                        DataType.STRING, OperatorType.EQUAL, OperatorType.LESSER, "z"));
        assertThrows(IllegalArgumentException.class,
                () -> DisplayRulesService.validateRange(
                        DataType.NUMBER, OperatorType.GREATER, OperatorType.GREATER_OR_EQUAL, 1));

        DisplayRulesService.validateRange(
                DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, OperatorType.LESSER, 1);
    }

    @Test
    void priorityBorderRangesMeetAtConfiguredBoundaries() {
        DisplayRulesService service = new DisplayRulesService(null, null, null, null);
        List<DisplayRule> rules = List.of(
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.0,
                        OperatorType.LESSER, 0.4, null, "#3b82f6", 1.0, 1),
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.4,
                        OperatorType.LESSER, 0.8, null, "#f97316", 2.0, 2),
                new DisplayRule("priority", DataType.NUMBER, OperatorType.GREATER_OR_EQUAL, 0.8,
                        OperatorType.LESSER_OR_EQUAL, 1.0, null, "#facc15", 3.0, 3));

        assertEquals("#3b82f6", styleFor(service, rules, 0.0).getBorderColor());
        assertEquals("#f97316", styleFor(service, rules, 0.4).getBorderColor());
        assertEquals("#facc15", styleFor(service, rules, 0.8).getBorderColor());
        assertEquals("#facc15", styleFor(service, rules, 1.0).getBorderColor());
    }

    private DisplayRuleVisualStyle styleFor(DisplayRulesService service, List<DisplayRule> rules, double priority) {
        return service.applyDisplayRules(Map.of("priority", priority), Map.of(), rules);
    }
}
