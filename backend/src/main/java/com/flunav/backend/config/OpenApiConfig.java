package com.flunav.backend.config;

import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
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
