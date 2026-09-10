package com.flunav.backend.utils;

import com.flunav.backend.services.EventProcessor;
import flunav.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ControllerHelper {

    private final EventProcessor eventProcessor;
    private final com.flunav.backend.services.SimulationService simulationService;
    private final com.flunav.backend.services.TimeService timeService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(ControllerHelper.class);

    @Autowired
    public ControllerHelper(EventProcessor eventProcessor,
            com.flunav.backend.services.SimulationService simulationService,
            com.flunav.backend.services.TimeService timeService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.eventProcessor = eventProcessor;
        this.simulationService = simulationService;
        this.timeService = timeService;
        this.objectMapper = objectMapper;
    }

    /**
     * A shared method to process a domain event and log the success.
     *
     * @param event The domain event to process.
     * @return A CompletableFuture containing the result map from the event
     *         processor.
     */
    public CompletableFuture<Map<String, Object>> processAndLogEvent(DomainEvent event) {
        var state = simulationService.getCurrentSimulation();
        if (state != null) {
            synchronized (state.getExecutionLock()) {
                var timestamp = simulationService.getSimulationClock(state);
                try (var virtualTime = timeService.enterVirtualTime(timestamp)) {
                    // Round-trip through the existing Jackson contract to keep immutable event DTOs intact.
                    com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.valueToTree(event);
                    payload.put("timestamp", timestamp.toString());
                    DomainEvent scenarioEvent = objectMapper.treeToValue(payload, DomainEvent.class);
                    return eventProcessor.process(scenarioEvent, true);
                } catch (Exception failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
        }
        return eventProcessor.process(event, true)
                .thenApply(resultMap -> {

                    logger.debug("Successfully processed event of type {}",
                            event.getEventType());
                    return resultMap;
                });
    }
}
