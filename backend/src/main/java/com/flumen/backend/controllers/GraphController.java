package com.flumen.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flumen.backend.models.graph.GraphData;
import com.flumen.backend.services.ClickHouseService;
import com.flumen.backend.services.GraphService;
import com.flumen.backend.services.HistoricalGraphBuilder;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph", description = "APIs for retrieving graph data")
public class GraphController {
    private static final Logger logger = LoggerFactory.getLogger(GraphController.class);

    private final HistoricalGraphBuilder historicalGraphBuilder;
    private final GraphService graphService;

    public GraphController(HistoricalGraphBuilder historicalGraphBuilder, GraphService graphService) {
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.graphService = graphService;
    }

    @GetMapping
    @Operation(summary = "Get the current state of the graph")
    public ResponseEntity<GraphData> getGraphData() {
        return ResponseEntity.ok(graphService.getGraphData());
    }

    @PostMapping
    @Operation(summary = "Restore the graph to a specific time")
    public ResponseEntity<Void> restoreGraph(Instant time) {
        historicalGraphBuilder.rebuildGraphState(time);
        return ResponseEntity.noContent().build();
    }
} 