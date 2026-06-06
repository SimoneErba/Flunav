package com.flunav.backend.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

class LoggingConfigurationTests {

    @TempDir
    Path logDirectory;

    @Test
    void rollingFileContainsSingleLineJsonInfoAndExceptionRecords() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putProperty("LOG_PATH", logDirectory.toString());

        URL configuration = getClass().getClassLoader().getResource("logback-spring.xml");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(configuration);
        context.start();

        Logger logger = context.getLogger("com.flunav.backend.logging.LoggingConfigurationTests");
        logger.setLevel(Level.DEBUG);
        logger.debug("debug-only");
        logger.atInfo()
                .addKeyValue("event_type", "TEST_EVENT")
                .addKeyValue("event_id", "event-1")
                .log("processed event {}", "event-1");
        logger.error("failed event {}", "event-2", new IllegalStateException("failure"));

        Path logFile = logDirectory.resolve("flumen.json");
        for (int attempt = 0; attempt < 20 && Files.size(logFile) == 0; attempt++) {
            Thread.sleep(50);
        }
        context.stop();

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode info = objectMapper.readTree(lines.get(0));
        JsonNode error = objectMapper.readTree(lines.get(1));

        assertEquals("INFO", info.get("level").asText());
        assertEquals("processed event event-1", info.get("message").asText());
        assertEquals("processed event {}", info.get("message_template").asText());
        assertEquals("TEST_EVENT", info.get("event_type").asText());
        assertEquals("backend", info.get("application").asText());
        assertEquals("local", info.get("environment").asText());
        assertFalse(lines.stream().anyMatch(line -> line.contains("debug-only")));

        assertEquals("ERROR", error.get("level").asText());
        assertTrue(error.get("stack_trace").asText().contains("IllegalStateException: failure"));
        assertTrue(lines.get(1).startsWith("{"));
        assertTrue(lines.get(1).endsWith("}"));
    }
}
