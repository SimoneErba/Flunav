package com.flunav.backend.controllers;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.analytics.AnomalyNotification;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.TimeService;
import com.flunav.backend.services.WebSocketService;

import flunav.events.ComponentAlarmClearedEvent;
import flunav.types.ComponentType;

@RestController
@RequestMapping("/api/components")
public class ComponentAlarmController {
    private final EventProcessor eventProcessor;
    private final TimeService timeService;
    private final WebSocketService webSockets;

    public ComponentAlarmController(EventProcessor eventProcessor, TimeService timeService, WebSocketService webSockets) {
        this.eventProcessor = eventProcessor;
        this.timeService = timeService;
        this.webSockets = webSockets;
    }

    @PostMapping("/{componentType}/{componentId}/alarms/{alarmId}/clear")
    public ResponseEntity<Map<String, Object>> clearAlarm(@PathVariable ComponentType componentType,
            @PathVariable String componentId, @PathVariable String alarmId) {
        var timestamp = timeService.now();
        Map<String, Object> result = eventProcessor
                .process(new ComponentAlarmClearedEvent(alarmId, componentId, componentType, timestamp), true).join();
        if (!"IGNORED_DUPLICATE".equals(result.get("status"))) {
            webSockets.broadcastAnomaly(DatabaseContextHolder.getSimulationId(),
                    new AnomalyNotification("ALARM_CLEARED", null, null, alarmId, componentId, timestamp));
        }
        return ResponseEntity.ok(result);
    }
}
