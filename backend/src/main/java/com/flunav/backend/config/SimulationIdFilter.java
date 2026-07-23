package com.flunav.backend.config;

import com.flunav.backend.context.DatabaseContextHolder;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.flunav.backend.repositories.LiveSimulationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class SimulationIdFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(SimulationIdFilter.class);
    private final LiveSimulationRepository liveSimulationRepository;

    public SimulationIdFilter(LiveSimulationRepository liveSimulationRepository) {
        this.liveSimulationRepository = liveSimulationRepository;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        DatabaseContextHolder.SimulationContext simulationContext = null;
        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String simId = httpRequest.getHeader("X-Simulation-ID");
                if (simId != null && !simId.isBlank()) {
                    if (!liveSimulationRepository.exists(simId)) {
                        HttpServletResponse httpResponse = (HttpServletResponse) response;
                        httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        httpResponse.setContentType("application/json");
                        httpResponse.getWriter().write(
                                "{\"data\":null,\"meta\":{},\"error\":{\"code\":\"SIMULATION_NOT_FOUND\","
                                        + "\"message\":\"Simulation does not exist\"}}");
                        return;
                    }
                    simulationContext = DatabaseContextHolder.enterSimulationContext(simId);
                }
            }
            chain.doFilter(request, response);
        } finally {
            if (simulationContext != null) {
                simulationContext.close();
            }
        }
    }
}
