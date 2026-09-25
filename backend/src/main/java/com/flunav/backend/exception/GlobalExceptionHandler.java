package com.flunav.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles exceptions from CompletableFuture chains.
     * Unwraps the root cause and logs it.
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<Object> handleCompletionException(CompletionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof IllegalArgumentException illegalArgumentException) {
            return handleIllegalArgumentException(illegalArgumentException);
        }
        logger.error("Async Exception Occurred: {}", cause.getMessage(), cause);
        return buildErrorResponse(cause, "An unexpected error occurred during an asynchronous operation.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        logger.warn("Request body could not be parsed: {}", ex.getMessage());
        return buildErrorResponse(ex, "Request body contains an invalid value.", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolvedStatus = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = ex.getReason() != null ? ex.getReason() : resolvedStatus.getReasonPhrase();
        logger.warn("Request rejected with {}: {}", resolvedStatus, message);
        return buildErrorResponse(ex, message, resolvedStatus);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return buildErrorResponse(ex, "Access denied.", HttpStatus.FORBIDDEN);
    }

    /**
     * Handles exceptions related to invalid method arguments (e.g., bad request
     * body).
     * Returns a 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Illegal argument exception: {}", ex.getMessage());
        String message = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : "Invalid input provided.";
        return buildErrorResponse(ex, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * A catch-all handler for any other unhandled exceptions.
     * Returns a 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllOtherExceptions(Exception ex) {
        logger.error("An unhandled exception occurred: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex, "An unexpected internal server error occurred.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * A helper method to build the standardized error response body.
     */
    private ResponseEntity<Object> buildErrorResponse(Throwable ex, String message, HttpStatus status) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("exceptionType", ex.getClass().getSimpleName());

        return new ResponseEntity<>(body, status);
    }
}
