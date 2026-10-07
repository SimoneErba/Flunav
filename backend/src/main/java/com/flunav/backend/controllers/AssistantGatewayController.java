package com.flunav.backend.controllers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/assistant-api")
public class AssistantGatewayController {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String ASSISTANT_UNAVAILABLE_BODY =
            "{\"status\":\"unavailable\",\"error\":\"Assistant gateway unavailable\"}";
    private static final Logger logger = LoggerFactory.getLogger(AssistantGatewayController.class);

    private final HttpClient httpClient;
    private final String assistantGatewayUrl;

    @Autowired
    public AssistantGatewayController(
            @Value("${app.assistant.gateway-url:http://localhost:8090}") String assistantGatewayUrl) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build(), assistantGatewayUrl);
    }

    AssistantGatewayController(HttpClient httpClient, String assistantGatewayUrl) {
        this.httpClient = httpClient;
        this.assistantGatewayUrl = assistantGatewayUrl.replaceAll("/+$", "");
    }

    /**
     * Proxies assistant health through the backend so the frontend can use the same
     * API base URL for core Flunav and assistant requests.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health(HttpServletRequest servletRequest) {
        return forwardJson("GET", "/health", null, null, servletRequest);
    }

    /**
     * Creates or resumes an authenticated Trigger chat session. The gateway,
     * rather than browser payload data, binds the resulting session to its user
     * and simulation scope.
     */
    @PostMapping("/sessions/start")
    public ResponseEntity<String> startSession(
            @RequestBody String body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest servletRequest) {
        return forwardJson("POST", "/sessions/start", body, authorization, servletRequest);
    }

    /**
     * Refreshes the short-lived token for an already owned Trigger chat session.
     */
    @PostMapping("/sessions/token")
    public ResponseEntity<String> sessionToken(
            @RequestBody String body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest servletRequest) {
        return forwardJson("POST", "/sessions/token", body, authorization, servletRequest);
    }

    /**
     * Stops only the active turn for the authenticated session; the durable
     * chat can still receive a later follow-up message.
     */
    @PostMapping("/sessions/stop")
    public ResponseEntity<String> stopSession(
            @RequestBody String body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest servletRequest) {
        return forwardJson("POST", "/sessions/stop", body, authorization, servletRequest);
    }

    /**
     * Closes the authenticated assistant chat and lets the frontend create a
     * fresh Trigger session without retaining stale realtime state.
     */
    @PostMapping("/sessions/reset")
    public ResponseEntity<String> resetSession(
            @RequestBody String body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest servletRequest) {
        return forwardJson("POST", "/sessions/reset", body, authorization, servletRequest);
    }

    private ResponseEntity<String> forwardJson(
            String method,
            String path,
            String body,
            String authorization,
            HttpServletRequest servletRequest) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(assistantGatewayUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header("X-Forwarded-Proto", servletRequest.getScheme());

        if (authorization != null && !authorization.isBlank()) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        if ("POST".equals(method)) {
            requestBuilder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else {
            requestBuilder.GET();
        }

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                logger.warn("Assistant gateway {} {} returned HTTP {}: {}", method, path, response.statusCode(),
                        abbreviateForLog(response.body()));
            } else {
                logger.info("Assistant gateway {} {} returned HTTP {}", method, path, response.statusCode());
            }
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (IOException exception) {
            logger.warn("Assistant gateway {} {} could not be reached: {}", method, path, exception.getMessage());
            return assistantUnavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Assistant gateway {} {} request was interrupted", method, path);
            return assistantUnavailable();
        }
    }

    private ResponseEntity<String> assistantUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ASSISTANT_UNAVAILABLE_BODY);
    }

    private String abbreviateForLog(String responseBody) {
        if (responseBody == null || responseBody.length() <= 1024) {
            return responseBody;
        }
        return responseBody.substring(0, 1024) + "...";
    }
}
