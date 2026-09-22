package com.flunav.backend.config;

import com.flunav.backend.context.DatabaseContextHolder;
import com.flunav.backend.models.simulation.SimulationKind;
import com.flunav.backend.models.simulation.SimulationStatus;
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
                if (simId != null && !simId.isBlank() && !usesExplicitSimulationTarget(httpRequest)) {
                    var metadata = liveSimulationRepository.getState(simId).orElse(null);
                    if (metadata == null) {
                        HttpServletResponse httpResponse = (HttpServletResponse) response;
                        httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        httpResponse.setContentType("application/json");
                        httpResponse.getWriter().write(
                                "{\"data\":null,\"meta\":{},\"error\":{\"code\":\"SIMULATION_NOT_FOUND\","
                                        + "\"message\":\"Simulation does not exist\"}}");
                        return;
                    }
                    if (isGraphMutation(httpRequest)
                            && (metadata.kind() == SimulationKind.STANDARD
                                    || metadata.status() == SimulationStatus.BUILDING
                                    || metadata.status() == SimulationStatus.QUEUED
                                    || metadata.status() == SimulationStatus.FAILED)) {
                        HttpServletResponse httpResponse = (HttpServletResponse) response;
                        httpResponse.setStatus(HttpServletResponse.SC_CONFLICT);
                        httpResponse.setContentType("application/json");
                        httpResponse.getWriter().write(
                                "{\"data\":null,\"meta\":{},\"error\":{\"code\":\"WHAT_IF_REQUIRED\","
                                        + "\"message\":\"Graph mutations require a ready what-if simulation\"}}");
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

    private boolean isGraphMutation(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        String path = request.getRequestURI();
        return path.matches("/api/(items|locations|conveyors|positions)(/.*)?")
                || path.matches("/api/graph/import(/.*)?");
    }

    /** Simulation lifecycle endpoints resolve their target from the path or body. */
    private boolean usesExplicitSimulationTarget(HttpServletRequest request) {
        return request.getRequestURI().matches("/api/simulations(/.*)?");
    }
}
