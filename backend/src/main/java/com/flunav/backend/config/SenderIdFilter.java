package com.flunav.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import flunav.context.UserContextHolder;

import java.io.IOException;

@Component
public class SenderIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        UserContextHolder.SenderContext senderContext = null;
        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String id = httpRequest.getHeader("X-Sender-ID");
                if (id != null && !id.isBlank()) {
                    senderContext = UserContextHolder.enterSenderContext(id);
                }
            }

            chain.doFilter(request, response);

        } finally {
            if (senderContext != null) {
                senderContext.close();
            }
        }
    }
}
