package com.flunav.backend.services;

import com.flunav.backend.models.response.DisplayRuleColorResult;
import flunav.events.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

/** Domain reduction helpers; invoked only inside EventProcessor context and retry boundaries. */
@Service
final class ConfigurationReducer {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationReducer.class);
    private final DisplayRulesService displayRulesService;
    private final GraphService graphService;
    private final DestinationMappingService destinationMappingService;
    private final DestinationExitMappingService destinationExitMappingService;
    private final SensorMappingService sensorMappingService;
    private final EventReductionSupport support;

    ConfigurationReducer(
            DisplayRulesService displayRulesService,
            @Lazy GraphService graphService,
            DestinationMappingService destinationMappingService,
            DestinationExitMappingService destinationExitMappingService,
            SensorMappingService sensorMappingService,
            EventReductionSupport support) {
        this.displayRulesService = displayRulesService;
        this.graphService = graphService;
        this.destinationMappingService = destinationMappingService;
        this.destinationExitMappingService = destinationExitMappingService;
        this.sensorMappingService = sensorMappingService;
        this.support = support;
    }

    /** Applies derived state in the caller's context; history persistence stays in EventProcessor. */
    Map<String, Object> reduce(DomainEvent event, boolean shouldBroadcast) {
        return switch (event) {
            case MapDestinationsEvent e -> {
                destinationMappingService.saveMapDestinations(e);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case MapDestinationExitsEvent e -> {
                destinationExitMappingService.saveMappings(e);
                support.retryWaitingHighPriorityItems(e.getTimestamp(), shouldBroadcast);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case MapSensorMappingsEvent e -> {
                sensorMappingService.saveMappings(e);
                yield Map.of("status", "PROCESSED_SUCCESSFULLY");
            }

            case MapDisplayRulesEvent e -> {
                displayRulesService.updateDisplayRules(e.getRules());
                DisplayRuleColorResult styles = graphService.computeColors(e.getRules());
                yield Map.of("status", "PROCESSED_SUCCESSFULLY", "colors", styles);
            }
            default -> throw new IllegalArgumentException("Unsupported event: " + event.getEventType());
        };
    }



}
