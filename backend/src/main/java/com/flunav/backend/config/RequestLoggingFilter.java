package com.flunav.backend.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.time.Instant;

@Component
public class RequestLoggingFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        long startTime = System.currentTimeMillis();
        String path = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        logger.info("Request: {} {} - Started at {}", method, path, Instant.now());

        try {
            chain.doFilter(request, response);
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Response: {} {} - Status: {} - Completed in {}ms",
                    method, path, httpResponse.getStatus(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Error processing {} {}: {} - After {}ms",
                    method, path, e.getMessage(), duration, e);
            throw e;
        }
    }
}