package com.flunav.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class StopwatchService {

    private static final Logger logger = LoggerFactory.getLogger(StopwatchService.class);

    private final Map<String, Long> timers = new HashMap<>();

    public String start() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        timers.put(id, System.nanoTime());
        return id;
    }

    public void stop(String id, String message) {
        Long start = timers.remove(id);
        if (start == null) {
            logger.warn("[STOPWATCH] Unknown id: {}", id);
            return;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        logger.info("[STOPWATCH] {}: {}ms", message, ms);
    }
}