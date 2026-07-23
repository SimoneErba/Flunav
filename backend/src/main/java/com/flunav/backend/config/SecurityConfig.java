package com.flunav.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Value("${app.demo-mode:false}")
    private boolean demoMode;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. Disabilita CSRF (Non serve per API Stateless con JWT)
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Abilita CORS (Per permettere al Frontend React di chiamare il Backend)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 3. Gestione Sessione: STATELESS (Niente cookie di sessione, usiamo solo JWT)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3b. Gestione Eccezioni: Return 401 instead of 403 for unauthenticated
                // requests
                .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))

                // 4. Regole di Autorizzazione URL
                .authorizeHttpRequests(auth -> {
                    if (demoMode) {
                        auth.anyRequest().permitAll();
                    } else {
                        auth
                                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                                .requestMatchers("/api/auth/me").authenticated()
                                // Endpoint pubblici (Login, Swagger, WebSocket handshake)
                                .requestMatchers("/api/auth/**").permitAll()
                                .requestMatchers("/ws/**").permitAll() // WebSocket
                                .requestMatchers("/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**").permitAll() // Documentazione
                                                                                                                  // API
                                // Tutto il resto richiede autenticazione
                                .anyRequest().authenticated();
                    }
                })

                // 5. Aggiungi il tuo filtro JWT prima del filtro standard di Spring
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Configurazione CORS per permettere al frontend (es. localhost:5173) di
     * comunicare.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Modifica con l'URL del tuo frontend in produzione
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Sender-ID", "X-Simulation-ID",
                "X-Flumen-Service-Token"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
