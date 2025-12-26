package com.flunav.backend.config;

import com.flunav.backend.context.DatabaseContextHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class SimulationIdFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(SimulationIdFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String simId = httpRequest.getHeader("X-Simulation-ID");
                if (simId != null && !simId.isBlank()) {
                    // Store in ThreadLocal
                    DatabaseContextHolder.enterSimulationContext(simId);
                }
            }
            chain.doFilter(request, response);
        } finally {
            // Cleanup
            DatabaseContextHolder.clearSimulation();
        }
    }
}