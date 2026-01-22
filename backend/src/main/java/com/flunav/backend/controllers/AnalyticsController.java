package com.flunav.backend.controllers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flunav.backend.models.response.ThroughputMetric;
import com.flunav.backend.services.ClickHouseService;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final ClickHouseService clickHouseService;

    public AnalyticsController(ClickHouseService clickHouseService) {
        this.clickHouseService = clickHouseService;
    }

    @GetMapping("/throughput/history")
    public CompletableFuture<ResponseEntity<List<ThroughputMetric>>> getThroughputHistory(
            @RequestParam(defaultValue = "24") int hours) {
        return clickHouseService.getThroughputHistory(hours)
                .thenApply(ResponseEntity::ok);
    }
}
