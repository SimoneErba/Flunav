package com.flunav.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();

        modelMapper.typeMap(com.flunav.backend.domain.Conveyor.class,
                com.flunav.backend.models.response.ConveyorResponse.class).addMappings(mapper -> {
                    mapper.map(com.flunav.backend.domain.Conveyor::getSourceLocationId,
                            com.flunav.backend.models.response.ConveyorResponse::setSourceId);
                    mapper.map(com.flunav.backend.domain.Conveyor::getTargetLocationId,
                            com.flunav.backend.models.response.ConveyorResponse::setTargetId);
                });

        return modelMapper;
    }

    @Autowired
    private DemoModeInterceptor demoModeInterceptor;

    public WebConfig(DemoModeInterceptor demoModeInterceptor) {
        this.demoModeInterceptor = demoModeInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(demoModeInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}