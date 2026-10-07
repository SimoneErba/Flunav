package com.flunav.backend.config;

import com.flunav.backend.domain.Role;
import com.flunav.backend.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String ASSISTANT_API_PATH_PREFIX = "/api/assistant-api/";

    private final JwtUtils jwtUtils;

    @Value("${app.demo-mode:false}")
    private boolean demoMode;

    @Value("${app.assistant.service-token:}")
    private String assistantServiceToken;

    public JwtAuthenticationFilter(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final boolean assistantRequest = request.getRequestURI().startsWith(ASSISTANT_API_PATH_PREFIX);

        String username = null;
        String jwt = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
            if (jwtUtils.validateToken(jwt)) {
                username = jwtUtils.getUsernameFromToken(jwt);
                Role role = jwtUtils.getRoleFromToken(jwt);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            username, null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name())));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                if (assistantRequest) {
                    logger.info("Assistant API request authenticated for user {}", username);
                }
            } else if (assistantRequest) {
                logger.warn("Assistant API request rejected because the bearer JWT is invalid");
            }
        } else if (assistantRequest) {
            logger.warn("Assistant API request rejected because the Authorization bearer token is missing");
        } else if (isValidAssistantServiceRequest(request)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "flunav-assistant", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_VIEWER")));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else if (demoMode && SecurityContextHolder.getContext().getAuthentication() == null) {
            // In demo mode, if no token is provided, we set a default authentication
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "demo-user", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isValidAssistantServiceRequest(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/analytics/investigation/")
                || assistantServiceToken == null || assistantServiceToken.isBlank()) {
            return false;
        }
        String supplied = request.getHeader("X-Flunav-Service-Token");
        return supplied != null && MessageDigest.isEqual(
                assistantServiceToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
