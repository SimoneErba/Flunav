package com.flunav.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.gateway.config.OpcConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpcIngestionService {

    private final OpcConfiguration config;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    // Map to hold the state of each PLC connection
    private final Map<String, PlcConnectionWrapper> connections = new ConcurrentHashMap<>();
    private static final String EXCHANGE_NAME = "item-events-exchange";

    // --- INNER CLASS TO MANAGE STATE ---
    private static class PlcConnectionWrapper {
        OpcConfiguration.OpcConnectionConfig config;
        OpcUaClient client;
        boolean isConnected = false;
        int retryCount = 0;

        public PlcConnectionWrapper(OpcConfiguration.OpcConnectionConfig config) {
            this.config = config;
        }
    }

    @PostConstruct
    public void init() {
        // Initialize wrappers but don't connect yet. The Watchdog will handle it.
        if (config.getConnections() != null) {
            for (OpcConfiguration.OpcConnectionConfig connConfig : config.getConnections()) {
                connections.put(connConfig.getName(), new PlcConnectionWrapper(connConfig));
            }
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Shutting down OPC UA Clients...");
        connections.forEach((name, wrapper) -> {
            if (wrapper.client != null) {
                try {
                    wrapper.client.disconnect().get();
                    log.info("Disconnected from {}", name);
                } catch (Exception e) {
                    log.warn("Error disconnecting from {}", name, e);
                }
            }
        });
    }

    /**
     * THE WATCHDOG
     * Runs every 10 seconds. Checks all PLCs.
     * If disconnected, attempts to reconnect and re-subscribe.
     */
    @Scheduled(fixedDelay = 10000)
    public void connectionWatchdog() {
        connections.forEach((name, wrapper) -> {
            try {
                if (wrapper.client == null) {
                    // Initial connection attempt
                    connectAndSubscribe(wrapper);
                } else {
                    // Check health
                    try {
                        // Fast check: read the ServerStatus node (i=2259)
                        wrapper.client.getSession().get().read(
                                0.0,
                                TimestampsToReturn.Both,
                                Collections.singletonList(new ReadValueId(
                                        new NodeId(0, 2259), // Server_ServerStatus_State
                                        AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE)))
                                .get();

                        wrapper.isConnected = true;
                        wrapper.retryCount = 0; // Reset retry count on success
                    } catch (Exception e) {
                        log.warn("PLC {} seems down. Attempting reconnection...", name);
                        wrapper.isConnected = false;
                        // Force disconnect to clean up
                        try {
                            wrapper.client.disconnect().get();
                        } catch (Exception ignored) {
                        }
                        wrapper.client = null; // Reset client to force full recreation

                        // Attempt immediate reconnection
                        connectAndSubscribe(wrapper);
                    }
                }
            } catch (Exception e) {
                wrapper.retryCount++;
                long backoff = Math.min(60, (long) Math.pow(2, wrapper.retryCount)); // Exponential backoff cap at 60s
                log.error("Failed to connect to PLC: {}. Retry #{} in {}s. Error: {}",
                        name, wrapper.retryCount, backoff, e.getMessage());
            }
        });
    }

    private void connectAndSubscribe(PlcConnectionWrapper wrapper) throws Exception {
        String endpointUrl = wrapper.config.getEndpointUrl() != null ? wrapper.config.getEndpointUrl()
                : config.getServer().getEndpointUrl();

        log.info("Connecting to PLC: {} at {}...", wrapper.config.getName(), endpointUrl);

        // 1. Discovery (Essential for production to get correct endpoints)
        List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(endpointUrl).get();

        OpcUaClientConfig clientConfig = OpcUaClientConfig.builder()
                .setApplicationName(LocalizedText.english("Flunav Gateway - " + wrapper.config.getName()))
                .setApplicationUri("urn:flunav:gateway")
                .setEndpoint(
                        endpoints.stream().findFirst().orElseThrow(() -> new RuntimeException("No endpoints found")))
                .setRequestTimeout(uint(5000)) // 5s timeout
                .build();

        OpcUaClient client = OpcUaClient.create(clientConfig);
        client.connect().get();

        wrapper.client = client;
        wrapper.isConnected = true;
        log.info("PLC {} Connected! Creating subscriptions...", wrapper.config.getName());

        // 2. Resolve and Create Subscriptions
        List<OpcConfiguration.SubscriptionRule> rules = resolveRules(wrapper.config.getSubscriptions());
        createSubscriptionsForClient(wrapper.client, rules);
    }

    // --- RULE RESOLUTION LOGIC ---

    private List<OpcConfiguration.SubscriptionRule> resolveRules(List<OpcConfiguration.SubscriptionConfig> rawConfigs) {
        List<OpcConfiguration.SubscriptionRule> resolvedRules = new ArrayList<>();
        if (rawConfigs == null)
            return resolvedRules;

        for (OpcConfiguration.SubscriptionConfig raw : rawConfigs) {
            try {
                OpcConfiguration.SubscriptionRule rule;

                if (raw.getUseTemplate() != null) {
                    // Strategy A: Template
                    OpcConfiguration.SubscriptionRule template = config.getTemplates().get(raw.getUseTemplate());
                    if (template == null) {
                        log.error("Template '{}' not found. Skipping rule.", raw.getUseTemplate());
                        continue;
                    }
                    rule = applyTemplate(template, raw);
                } else {
                    // Strategy B: Custom
                    rule = new OpcConfiguration.SubscriptionRule();
                    rule.setName(raw.getName());
                    rule.setTriggerNode(raw.getTriggerNode());
                    rule.setTriggerCondition(raw.getTriggerCondition());
                    rule.setEventType(raw.getEventType());
                    rule.setStaticAttributes(raw.getStaticAttributes());
                    rule.setPayloadMapping(raw.getPayloadMapping());
                }

                if (rule.getTriggerNode() == null || rule.getEventType() == null) {
                    log.warn("Skipping invalid rule: {}", rule);
                    continue;
                }
                resolvedRules.add(rule);

            } catch (Exception e) {
                log.error("Error resolving rule", e);
            }
        }
        return resolvedRules;
    }

    private OpcConfiguration.SubscriptionRule applyTemplate(OpcConfiguration.SubscriptionRule template,
            OpcConfiguration.SubscriptionConfig instance) {
        OpcConfiguration.SubscriptionRule rule = new OpcConfiguration.SubscriptionRule();
        Map<String, String> vars = instance.getVariables() != null ? instance.getVariables() : Collections.emptyMap();

        // Name
        rule.setName(instance.getName() != null ? instance.getName() : template.getName() + "_" + vars.values());

        // Trigger Node (Replace vars)
        rule.setTriggerNode(replaceVars(template.getTriggerNode(), vars));

        // Simple Fields
        rule.setTriggerCondition(template.getTriggerCondition());
        rule.setEventType(template.getEventType());

        // Merge Static Attributes
        Map<String, Object> mergedStatic = new HashMap<>();
        if (template.getStaticAttributes() != null)
            mergedStatic.putAll(template.getStaticAttributes());
        if (instance.getStaticAttributes() != null)
            mergedStatic.putAll(instance.getStaticAttributes());
        rule.setStaticAttributes(mergedStatic);

        // Merge Payload Mapping
        Map<String, String> mergedPayload = new HashMap<>();
        if (template.getPayloadMapping() != null) {
            template.getPayloadMapping().forEach((k, v) -> mergedPayload.put(k, replaceVars(v, vars)));
        }
        if (instance.getPayloadMapping() != null) {
            mergedPayload.putAll(instance.getPayloadMapping());
        }
        rule.setPayloadMapping(mergedPayload);

        return rule;
    }

    private String replaceVars(String input, Map<String, String> vars) {
        if (input == null)
            return null;
        String result = input;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    // --- SUBSCRIPTION LOGIC ---

    private void createSubscriptionsForClient(OpcUaClient client, List<OpcConfiguration.SubscriptionRule> rules)
            throws Exception {
        if (rules.isEmpty())
            return;

        // Create one subscription per client to manage all items
        UaSubscription subscription = client.getSubscriptionManager().createSubscription(100.0).get();

        for (OpcConfiguration.SubscriptionRule rule : rules) {
            try {
                NodeId triggerNodeId = NodeId.parse(rule.getTriggerNode());

                ReadValueId readValueId = new ReadValueId(triggerNodeId, AttributeId.Value.uid(), null,
                        QualifiedName.NULL_VALUE);

                MonitoringParameters parameters = new MonitoringParameters(
                        uint(subscription.getMonitoredItems().size() + 1),
                        50.0, // Sampling Interval
                        null,
                        uint(10), // Queue Size
                        true // Discard Oldest
                );

                MonitoredItemCreateRequest request = new MonitoredItemCreateRequest(readValueId,
                        MonitoringMode.Reporting, parameters);

                subscription.createMonitoredItems(
                        TimestampsToReturn.Both,
                        Collections.singletonList(request),
                        (item, id) -> item.setValueConsumer((subItem, value) -> onTriggerFired(client, rule, value)))
                        .get();

                log.info("Created monitor for rule: {}", rule.getName());

            } catch (Exception e) {
                log.error("Failed to create subscription for rule: {}", rule.getName(), e);
            }
        }
    }

    // --- EVENT PROCESSING ---

    private void onTriggerFired(OpcUaClient client, OpcConfiguration.SubscriptionRule rule, DataValue value) {
        String currentValue = String.valueOf(value.getValue().getValue());
        log.debug("Trigger '{}' changed to: {}", rule.getName(), currentValue);

        // 1. Check Condition
        if (!currentValue.equalsIgnoreCase(rule.getTriggerCondition())) {
            return;
        }

        log.info("Rule '{}' matched! Reading payload...", rule.getName());

        Map<String, Object> eventData = new HashMap<>();

        // 2. Add Static Attributes
        if (rule.getStaticAttributes() != null) {
            eventData.putAll(rule.getStaticAttributes());
        }

        // 3. Read Dynamic Attributes (Synchronous Read from the specific client)
        if (rule.getPayloadMapping() != null && !rule.getPayloadMapping().isEmpty()) {
            try {
                readDynamicPayload(client, rule.getPayloadMapping(), eventData);
            } catch (Exception e) {
                log.error("Failed to read payload for rule {}", rule.getName(), e);
                return; // Abort if data read fails
            }
        }

        // 4. Publish
        publishEvent(rule.getEventType(), eventData);
    }

    private void readDynamicPayload(OpcUaClient client, Map<String, String> mapping, Map<String, Object> eventData)
            throws Exception {
        List<String> keys = new ArrayList<>(mapping.keySet());
        List<NodeId> nodeIdsToRead = new ArrayList<>();

        for (String key : keys) {
            nodeIdsToRead.add(NodeId.parse(mapping.get(key)));
        }

        // Bulk read from the specific client
        List<DataValue> values = client.readValues(0.0, TimestampsToReturn.Both, nodeIdsToRead).get();

        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Object val = values.get(i).getValue().getValue();

            // Use helper to handle "properties.weight" nesting
            addNestedValue(eventData, key, val);
        }
    }

    @SuppressWarnings("unchecked")
    private void addNestedValue(Map<String, Object> rootMap, String key, Object value) {
        if (!key.contains(".")) {
            rootMap.put(key, value);
            return;
        }

        String[] parts = key.split("\\.");
        Map<String, Object> currentMap = rootMap;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            currentMap = (Map<String, Object>) currentMap.computeIfAbsent(part, k -> new HashMap<>());
        }

        currentMap.put(parts[parts.length - 1], value);
    }

    // --- RABBITMQ RESILIENCE ---

    /**
     * Tries to send to RabbitMQ.
     * If it fails (broker down), it retries 3 times with backoff (1s, 2s, 4s).
     */
    @Retryable(retryFor = { Exception.class }, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    private void publishEvent(String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("eventType", eventType);
            message.put("timestamp", java.time.Instant.now().toString());
            message.put("eventId", java.util.UUID.randomUUID().toString());

            // Merge data
            message.putAll(data);

            String json = objectMapper.writeValueAsString(message);

            // Use entityId as routing key if present
            String routingKey = (String) data.getOrDefault("entityId", "default");

            rabbitTemplate.convertAndSend(EXCHANGE_NAME, routingKey, json);
            log.debug("Published event to RabbitMQ: {}", json);

        } catch (Exception e) {
            log.error("Error publishing to RabbitMQ", e);
            throw new RuntimeException(e); // Throw to trigger @Retryable
        }
    }

    /**
     * Fallback method if RabbitMQ is completely dead after retries.
     */
    @Recover
    public void recoverPublish(Exception e, String eventType, Map<String, Object> data) {
        log.error("CRITICAL: RabbitMQ is unreachable. Event DROPPED: {}. Data: {}", eventType, data);
        // In a real scenario, write to a local file here for later replay
    }
}