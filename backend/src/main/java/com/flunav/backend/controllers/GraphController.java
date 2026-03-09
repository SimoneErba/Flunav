package com.flunav.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flunav.backend.models.graph.GraphData;
import com.flunav.backend.services.GraphService;
import com.flunav.backend.services.HistoricalGraphBuilder;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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

    private final GraphService graphService;
    private final ObjectMapper objectMapper;
    private final HistoricalGraphBuilder historicalGraphBuilder;
    private static final String FILE_EXTENSION = ".flugraph";

    public GraphController(GraphService graphService, HistoricalGraphBuilder historicalGraphBuilder) {
        this.graphService = graphService;
        this.historicalGraphBuilder = historicalGraphBuilder;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @GetMapping
    @Operation(summary = "Get the current state of the graph")
    public ResponseEntity<GraphData> getGraphData() {
        return ResponseEntity.ok(graphService.getGraphData());
    }

    /**
     * GET /api/graph/export
     *
     * Downloads the current graph state as a .flugraph file (JSON under the hood).
     * The filename includes a timestamp so exports don't overwrite each other.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportGraph() {
        try {
            GraphData graphData = graphService.getGraphData();

            byte[] fileBytes = objectMapper.writeValueAsBytes(graphData);

            String filename = "graph-" + DateTimeFormatter
                    .ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .withZone(ZoneId.of("UTC"))
                    .format(Instant.now()) + FILE_EXTENSION;

            logger.info("Exporting graph snapshot: {} ({} locations, {} conveyors, {} items)",
                    filename,
                    graphData.getLocations() != null ? graphData.getLocations().size() : 0,
                    graphData.getConveyors() != null ? graphData.getConveyors().size() : 0,
                    graphData.getItems() != null ? graphData.getItems().size() : 0);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(fileBytes.length)
                    .body(fileBytes);

        } catch (Exception e) {
            logger.error("Failed to export graph", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/graph/import
     *
     * Accepts a .flugraph file upload and loads it into the graph service.
     * Replaces the current graph state entirely.
     *
     * Example usage with curl:
     * curl -X POST http://localhost:8080/api/graph/import \
     * -F "file=@graph-2024-01-01_12-00-00.flugraph"
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importGraph(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ImportResult(false, "Uploaded file is empty", null));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.endsWith(FILE_EXTENSION)) {
            logger.warn("Import attempted with unexpected file extension: {}", originalFilename);
            // Warn but don't reject — the user might have renamed the file
        }

        try {
            byte[] bytes = file.getBytes();
            GraphData graphData = objectMapper.readValue(bytes, GraphData.class);

            // Validate the parsed data has at minimum some structure
            if (graphData.getLocations() == null && graphData.getConveyors() == null) {
                return ResponseEntity.badRequest()
                        .body(new ImportResult(false,
                                "File appears to be empty or invalid — no locations or conveyors found", null));
            }

            historicalGraphBuilder.restoreFromSnapshotData(graphData);

            String summary = String.format("Loaded %d locations, %d conveyors, %d items",
                    graphData.getLocations() != null ? graphData.getLocations().size() : 0,
                    graphData.getConveyors() != null ? graphData.getConveyors().size() : 0,
                    graphData.getItems() != null ? graphData.getItems().size() : 0);

            logger.info("Graph import successful from '{}': {}", originalFilename, summary);
            return ResponseEntity.ok(new ImportResult(true, summary, graphData.getTimestamp()));

        } catch (IOException e) {
            logger.error("Failed to parse graph import file '{}'", originalFilename, e);
            return ResponseEntity.badRequest()
                    .body(new ImportResult(false, "Could not parse file: " + e.getMessage(), null));
        } catch (Exception e) {
            logger.error("Failed to load imported graph from '{}'", originalFilename, e);
            return ResponseEntity.internalServerError()
                    .body(new ImportResult(false, "Import failed: " + e.getMessage(), null));
        }
    }

    /**
     * Simple response body for the import endpoint.
     */
    public record ImportResult(boolean success, String message, Instant sourceTimestamp) {
    }
}