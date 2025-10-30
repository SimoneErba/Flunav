package com.fiumen.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

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
        logger.error("Async Exception Occurred: {}", cause.getMessage(), cause);
        return buildErrorResponse(cause, "An unexpected error occurred during an asynchronous operation.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handles exceptions related to invalid method arguments (e.g., bad request body).
     * Returns a 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Illegal argument exception: {}", ex.getMessage()); // Warn level is often sufficient for client errors
        return buildErrorResponse(ex, "Invalid input provided.", HttpStatus.BAD_REQUEST);
    }

    /**
     * A catch-all handler for any other unhandled exceptions.
     * Returns a 500 Internal Server Error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAllOtherExceptions(Exception ex) {
        logger.error("An unhandled exception occurred: {}", ex.getMessage(), ex);
        return buildErrorResponse(ex, "An unexpected internal server error occurred.", HttpStatus.INTERNAL_SERVER_ERROR);
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