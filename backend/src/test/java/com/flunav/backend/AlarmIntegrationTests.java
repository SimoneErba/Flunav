package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flunav.backend.services.ClickHouseService;
import com.flunav.backend.services.ConveyorService;
import com.flunav.backend.services.EventProcessor;
import com.flunav.backend.services.LocationService;
import com.flunav.backend.models.input.LocationInput;

import flunav.events.AlarmClearedEvent;
import flunav.events.AlarmRaisedEvent;
import flunav.events.ConnectionActivatedEvent;
import flunav.events.ConnectionDeactivatedEvent;
import flunav.events.DomainEvent;
import flunav.types.AlarmSeverity;
import flunav.types.LocationType;

@SpringBootTest(properties = {
        "springwolf.enabled=false",
        "state-recovery.enabled=false",
        "graph-snapshot.enabled=false",
        "metric-snapshot.enabled=false"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AlarmIntegrationTests extends BaseIntegrationTest {
    private final EventProcessor eventProcessor;
    private final ConveyorService conveyorService;
    private final LocationService locationService;
    private final ClickHouseService clickHouseService;
    private final ObjectMapper objectMapper;

    AlarmIntegrationTests(
            EventProcessor eventProcessor,
            ConveyorService conveyorService,
            LocationService locationService,
            ClickHouseService clickHouseService,
            ObjectMapper objectMapper) {
        this.eventProcessor = eventProcessor;
        this.conveyorService = conveyorService;
        this.locationService = locationService;
        this.clickHouseService = clickHouseService;
        this.objectMapper = objectMapper;
    }

    @Test
    void stoppingAlarmsRespectOperatorIntentAndPersistHistory() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String source = "alarm-source-" + suffix;
        String target = "alarm-target-" + suffix;
        String conveyorId = "alarm-conveyor-" + suffix;
        createLocation(source);
        createLocation(target);
        conveyorService.createConveyor(conveyorId, source, target, "Alarm conveyor",
                10.0, 1.0, 0.0, true, true);

        AlarmRaisedEvent warning = new AlarmRaisedEvent(
                "warning-" + suffix, conveyorId, AlarmSeverity.WARNING, "BELT_DEGRADATION", false);
        DomainEvent restored = objectMapper.readValue(objectMapper.writeValueAsString(warning), DomainEvent.class);
        assertInstanceOf(AlarmRaisedEvent.class, restored);

        process(warning);
        process(warning);
        assertTrue(conveyorService.getConveyorById(conveyorId).isActive());
        assertEquals(1, conveyorService.getConveyorById(conveyorId).getActiveAlarms().size());

        AlarmRaisedEvent firstStop = new AlarmRaisedEvent(
                "stop-1-" + suffix, conveyorId, AlarmSeverity.CRITICAL, "MOTOR_FAULT", true);
        AlarmRaisedEvent secondStop = new AlarmRaisedEvent(
                "stop-2-" + suffix, conveyorId, AlarmSeverity.CRITICAL, "SAFETY_INTERLOCK", true);
        process(firstStop);
        process(secondStop);
        assertFalse(conveyorService.getConveyorById(conveyorId).isActive());

        AlarmClearedEvent firstClear = new AlarmClearedEvent(firstStop.getAlarmId(), conveyorId,
                firstStop.getSeverity(), firstStop.getTypology(), true);
        process(firstClear);
        process(firstClear);
        assertFalse(conveyorService.getConveyorById(conveyorId).isActive());

        process(new ConnectionDeactivatedEvent(conveyorId));
        process(new AlarmClearedEvent(secondStop.getAlarmId(), conveyorId, secondStop.getSeverity(),
                secondStop.getTypology(), true));
        assertFalse(conveyorService.getConveyorById(conveyorId).isActive());
        assertFalse(conveyorService.getConveyorById(conveyorId).isOperatorEnabled());

        process(new ConnectionActivatedEvent(conveyorId));
        assertTrue(conveyorService.getConveyorById(conveyorId).isActive());
        assertEquals(1, conveyorService.getConveyorById(conveyorId).getActiveAlarms().size());

        var history = clickHouseService.getAlarmHistory(
                firstStop.getAlarmId(), null, Instant.EPOCH, Instant.now().plusSeconds(1)).join();
        assertEquals(2, history.size());
        assertTrue(history.stream().allMatch(event -> event.stopsConveyor()));
    }

    @Test
    void alarmEventsRejectMissingIdentityAndTypology() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlarmRaisedEvent("", "conveyor", AlarmSeverity.WARNING, "FAULT", false));
        assertThrows(IllegalArgumentException.class,
                () -> new AlarmRaisedEvent("alarm", "conveyor", AlarmSeverity.WARNING, "  ", false));
        assertThrows(NullPointerException.class,
                () -> new AlarmClearedEvent("alarm", "conveyor", null, "FAULT", false));
    }

    private void process(DomainEvent event) {
        eventProcessor.process(event, false).join();
    }

    private void createLocation(String id) {
        LocationInput input = new LocationInput();
        input.setId(id);
        input.setName(id);
        input.setActive(true);
        input.setLatitude(0.0);
        input.setLongitude(0.0);
        input.setType(LocationType.GENERIC);
        input.setCapacity(10);
        input.setProperties(Map.of());
        locationService.createLocation(input);
    }
}
