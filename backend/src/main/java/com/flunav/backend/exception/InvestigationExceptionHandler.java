package com.flunav.backend.exception;

import java.time.Instant;
import java.util.concurrent.CompletionException;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.controllers.InvestigationAnalyticsController;
import com.flunav.backend.controllers.InvestigationAnalyticsController.ApiError;
import com.flunav.backend.controllers.InvestigationAnalyticsController.Envelope;
import com.flunav.backend.controllers.InvestigationAnalyticsController.Meta;

@Order(0)
@RestControllerAdvice(assignableTypes = InvestigationAnalyticsController.class)
public class InvestigationExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Envelope<Void>> handle(Exception exception) {
        Throwable cause = unwrap(exception);
        HttpStatus status = cause instanceof ResponseStatusException responseStatus
                ? HttpStatus.valueOf(responseStatus.getStatusCode().value())
                : cause instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = cause instanceof ResponseStatusException responseStatus && responseStatus.getReason() != null
                ? responseStatus.getReason()
                : status == HttpStatus.INTERNAL_SERVER_ERROR ? "Investigation request failed"
                        : cause.getMessage();
        String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND"
                : status == HttpStatus.BAD_REQUEST ? "INVALID_REQUEST" : "INVESTIGATION_ERROR";
        return ResponseEntity.status(status).body(new Envelope<>(
                null,
                new Meta(DatabaseContextHolder.getSimulationId(), Instant.now()),
                new ApiError(code, message)));
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
