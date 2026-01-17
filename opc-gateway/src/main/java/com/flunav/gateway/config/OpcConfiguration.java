package com.flunav.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "opc")
public class OpcConfiguration {

    // 1. Global/Default Server Config
    // This maps to "opc.server" in YAML
    private OpcServerConfig server;

    // 2. Templates
    // This maps to "opc.templates"
    private Map<String, SubscriptionRule> templates = new HashMap<>();

    // 3. Multiple Connections
    // This maps to "opc.connections"
    private List<OpcConnectionConfig> connections = new ArrayList<>();

    // --- INNER CLASSES ---

    @Data
    public static class OpcServerConfig {
        private String endpointUrl;
        private String username;
        private String password;
        private String securityPolicy; // Optional
    }

    @Data
    public static class OpcConnectionConfig {
        private String name;
        private String endpointUrl; // Can override global server
        private String username;
        private String password;
        private List<SubscriptionConfig> subscriptions = new ArrayList<>();
    }

    /**
     * Represents the raw entry in the YAML file.
     * It contains fields for BOTH approaches (Template vs Custom).
     */
    @Data
    public static class SubscriptionConfig {
        // --- Strategy A: Template ---
        private String useTemplate;
        private Map<String, String> variables;

        // --- Strategy B: Custom (Standard Rule Fields) ---
        private String name;
        private String triggerNode;
        private String triggerCondition;
        private String eventType;

        // Used in both strategies (merged)
        private Map<String, Object> staticAttributes = new HashMap<>();
        private Map<String, String> payloadMapping = new HashMap<>();
    }

    /**
     * The final, resolved rule object used by the logic.
     */
    @Data
    public static class SubscriptionRule {
        private String name;
        private String triggerNode;
        private String triggerCondition;
        private String eventType;
        private Map<String, Object> staticAttributes = new HashMap<>();
        private Map<String, String> payloadMapping = new HashMap<>();
    }
}