package com.flonav.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import flonav.context.UserContextHolder;

import java.io.IOException;

@Component
public class SenderIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String id = httpRequest.getHeader("X-Sender-ID");
                if (id != null && !id.isBlank()) {
                    UserContextHolder.setSenderId(id);
                }
            }

            chain.doFilter(request, response);

        } finally {
            UserContextHolder.clear();
        }
    }
}