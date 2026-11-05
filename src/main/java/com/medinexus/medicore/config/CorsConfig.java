package com.medinexus.medicore.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Restricted CORS for the REST API, so MediPortal (served from another origin
 * during development) can call {@code /api/**}.
 *
 * <p>Only the explicitly configured origins are allowed
 * ({@code medinexus.cors.allowed-origins}); wildcards are rejected. GET and POST
 * requests may include session cookies.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${medinexus.cors.allowed-origins}") String[] allowedOrigins) {
        if (Arrays.stream(allowedOrigins).anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException(
                    "medinexus.cors.allowed-origins must list explicit origins; wildcards are not allowed");
        }
        this.allowedOrigins = allowedOrigins.clone();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST")
                .allowCredentials(true);
    }
}
