package com.flunav.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.services.EventProcessor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import flunav.context.UserContextHolder;
import flunav.events.PathTraversedEvent;
import flunav.types.PositionType;

@SpringBootTest(properties = {
		"springwolf.enabled=false",
		"logging.level.eventprocessor=DEBUG"
})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BackendApplicationTests extends BaseIntegrationTest {

	private final EventProcessor eventProcessor;

	private Logger eventProcessorLogger;
	private ListAppender<ILoggingEvent> logAppender;

	BackendApplicationTests(EventProcessor eventProcessor) {
		this.eventProcessor = eventProcessor;
	}

	@BeforeEach
	void captureEventProcessorLogs() {
		eventProcessorLogger = (Logger) LoggerFactory.getLogger(EventProcessor.class);
		eventProcessorLogger.setLevel(Level.DEBUG);
		logAppender = new ListAppender<>();
		logAppender.start();
		eventProcessorLogger.addAppender(logAppender);
	}

	@AfterEach
	void stopCapturingEventProcessorLogs() {
		eventProcessorLogger.detachAppender(logAppender);
		logAppender.stop();
		DatabaseContextHolder.clearSimulation();
		UserContextHolder.clear();
	}

	@Test
	void contextLoads() {
	}

	@Test
	void eventProcessorLogsLiveAndSimulationMetadata() {
		PathTraversedEvent liveEvent;
		try (var senderContext = UserContextHolder.enterSenderContext("live-sender")) {
			liveEvent = pathEvent("live-item");
			eventProcessor.process(liveEvent, true).join();
		}

		PathTraversedEvent simulationEvent;
		try (var simulationContext = DatabaseContextHolder.enterSimulationContext("simulation-1");
				var senderContext = UserContextHolder.enterSenderContext("simulation-sender")) {
			simulationEvent = pathEvent("simulation-item");
			eventProcessor.process(simulationEvent, false).join();
		}

		ILoggingEvent liveCompletion = completionFor(liveEvent);
		assertEquals("LIVE", mdcValue(liveCompletion, "mode"));
		assertEquals("true", value(liveCompletion, "broadcast"));
		assertEquals("live-item", mdcValue(liveCompletion, "entity_id"));
		assertEquals("live-sender", mdcValue(liveCompletion, "sender_id"));
		assertNull(mdcValue(liveCompletion, "simulation_id"));

		ILoggingEvent simulationCompletion = completionFor(simulationEvent);
		assertEquals("SIMULATION", mdcValue(simulationCompletion, "mode"));
		assertEquals("false", value(simulationCompletion, "broadcast"));
		assertEquals("simulation-item", mdcValue(simulationCompletion, "entity_id"));
		assertEquals("simulation-1", mdcValue(simulationCompletion, "simulation_id"));
		assertEquals("simulation-sender", mdcValue(simulationCompletion, "sender_id"));
		assertTrue(Long.parseLong(value(simulationCompletion, "duration_ms")) >= 0);
	}

	private PathTraversedEvent pathEvent(String entityId) {
		return new PathTraversedEvent(
				entityId,
				"location-a",
				PositionType.LOCATION,
				"location-b",
				PositionType.LOCATION,
				List.of("location-a", "location-b"));
	}

	private ILoggingEvent completionFor(PathTraversedEvent event) {
		return logAppender.list.stream()
				.filter(log -> "Event processing completed".equals(log.getMessage()))
				.filter(log -> event.getEventId().equals(mdcValue(log, "event_id")))
				.findFirst()
				.orElseThrow();
	}

	private String mdcValue(ILoggingEvent event, String key) {
		return event.getMDCPropertyMap().get(key);
	}

	private String value(ILoggingEvent event, String key) {
		return event.getKeyValuePairs().stream()
				.filter(pair -> key.equals(pair.key))
				.map(pair -> String.valueOf(pair.value))
				.findFirst()
				.orElse(null);
	}
}
