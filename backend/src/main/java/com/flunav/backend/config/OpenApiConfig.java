package com.flunav.backend.config;

import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    /** Conveyor presets accept numeric failure defaults, rather than arbitrary JSON objects. */
    @Bean
    public OpenApiCustomizer conveyorPresetPropertiesSchema() {
        return api -> {
            var preset = api.getComponents().getSchemas().get("ConveyorPreset");
            if (preset != null) preset.addProperty("properties", new MapSchema().additionalProperties(new NumberSchema()));
            var creation = api.getComponents().getSchemas().get("CreateConveyorInput");
            if (creation != null) creation.addProperty("properties", new MapSchema().additionalProperties(new ComposedSchema()
                    .addAnyOfItem(new NumberSchema()).addAnyOfItem(new StringSchema())
                    .addAnyOfItem(new BooleanSchema()).addAnyOfItem(new ObjectSchema())));
        };
    }

    /** Shared event rules store Object values, but their API values are JSON scalars. */
    @Bean
    public OpenApiCustomizer displayRuleValueSchemas() {
        return api -> {
            var rules = api.getComponents().getSchemas().get("DisplayRule");
            if (rules == null) return;
            for (String field : new String[] { "value", "secondValue" }) {
                rules.addProperty(field, new ComposedSchema()
                        .addAnyOfItem(new StringSchema())
                        .addAnyOfItem(new NumberSchema())
                        .addAnyOfItem(new BooleanSchema()));
            }
        };
    }
}
