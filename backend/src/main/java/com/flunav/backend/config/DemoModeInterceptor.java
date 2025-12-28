package com.flunav.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class DemoModeInterceptor implements HandlerInterceptor {

    @Value("${app.demo-mode:false}")
    private boolean isDemoMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!isDemoMode)
            return true;

        // 2. Check if the target is a Controller Method (not a static resource)
        if (handler instanceof HandlerMethod) {
            HandlerMethod method = (HandlerMethod) handler;

            // 3. Check if the method has our @BlockInDemo annotation
            if (method.hasMethodAnnotation(BlockInDemo.class)) {

                // 4. Check if this is a "Live" edit (No Simulation ID)
                String simId = request.getHeader("X-Simulation-ID");

                if (simId == null || simId.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.getWriter().write("Action disabled in Demo Mode (Live View is protected)");
                    return false;
                }
            }
        }
        return true;
    }
}