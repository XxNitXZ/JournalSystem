package com.medinexus.medicore.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;

import com.medinexus.medicore.common.exception.ApiError;
import com.medinexus.medicore.security.jwt.CookieBearerTokenResolver;
import com.medinexus.medicore.security.jwt.JwtPrincipalConverter;
import com.medinexus.medicore.security.jwt.JwtSecurityConfig;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Messaging's security: stateless JWT cookie or Bearer token (from {@code medicore-common}), the Lab 1 message routes
 * with the same roles (moved here in NIT-336), the health check, and nothing else.
 */
@Configuration
@EnableWebSecurity
@Import(JwtSecurityConfig.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CookieBearerTokenResolver tokenResolver,
            JwtDecoder jwtDecoder, JwtPrincipalConverter principalConverter) throws Exception {
        // Tokens are checked by a manager that knows only JWTs (see the monolith's SecurityConfig, NIT-327).
        JwtAuthenticationProvider jwtProvider = new JwtAuthenticationProvider(jwtDecoder);
        jwtProvider.setJwtAuthenticationConverter(principalConverter);
        AuthenticationManager jwtAuthentication = new ProviderManager(jwtProvider);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(tokenResolver)
                        .jwt(jwt -> jwt.authenticationManager(jwtAuthentication))
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, 401))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, 403)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/messages/inbox/*/reply").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/messages/inbox",
                                "/api/messages/inbox/*").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/messages/conversations",
                                "/api/messages/conversations/*").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/messages/recipients").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.POST, "/api/messages").hasRole("PATIENT")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, 401))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, 403)))
                .build();
    }

    private static void writeError(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiError.of(HttpStatusCode.valueOf(status)).toJson());
    }
}
