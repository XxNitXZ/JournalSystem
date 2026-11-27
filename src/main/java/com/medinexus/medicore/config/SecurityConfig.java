package com.medinexus.medicore.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.security.jwt.CookieBearerTokenResolver;
import com.medinexus.medicore.security.jwt.JwtPrincipalConverter;
import com.medinexus.medicore.security.jwt.JwtSecurityConfig;
import com.medinexus.medicore.security.jwt.JwtTokenService;

import jakarta.servlet.DispatcherType;

/**
 * Stateless security (NIT-327): no HTTP session. Every request is authenticated by the signed JWT in the
 * {@code medicore_token} cookie (or an {@code Authorization: Bearer} header for service calls), which Spring
 * Security's OAuth2 resource server verifies. CSRF tokens are not used: the cookie is {@code SameSite=Strict}, so the
 * browser never sends it on a request started by another site.
 */
@Configuration
@EnableWebSecurity
@Import(JwtSecurityConfig.class)
public class SecurityConfig {

    /** Every role a person can have; service tokens ({@code ROLE_SERVICE}) are excluded. */
    private static final String[] USER_ROLES = {"PATIENT", "DOCTOR", "STAFF"};

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationService service) {
        return authentication -> {
            try {
                UserDto user = service.authenticate(authentication.getName(), (String) authentication.getCredentials());

                return UsernamePasswordAuthenticationToken.authenticated(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));

            } catch (IllegalArgumentException exception) {
                throw new BadCredentialsException("Invalid email or password");
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CookieBearerTokenResolver tokenResolver,
            JwtDecoder jwtDecoder, JwtPrincipalConverter principalConverter, JwtTokenService tokens) throws Exception {
        // Tokens are checked by a manager that knows only JWTs. Without it, Spring would fall back to the password
        // AuthenticationManager above for a rejected token and try it as an e-mail and password.
        JwtAuthenticationProvider jwtProvider = new JwtAuthenticationProvider(jwtDecoder);
        jwtProvider.setJwtAuthenticationConverter(principalConverter);
        AuthenticationManager jwtAuthentication = new ProviderManager(jwtProvider);
        return http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(tokenResolver)
                        .jwt(jwt -> jwt.authenticationManager(jwtAuthentication))
                        // Plain status codes without a body, as before.
                        .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register", "/api/auth/logout")
                                .permitAll()
                        // /api/auth/csrf is a temporary no-op kept for MediPortal until NIT-338.
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health",
                                "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Service-to-service endpoints (EPIC-02); never reachable with a user token.
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").hasAnyRole(USER_ROLES)
                        // The signed-in user's own account: read, change e-mail/phone, change password.
                        .requestMatchers(HttpMethod.GET, "/api/auth/profile").hasAnyRole(USER_ROLES)
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/profile").hasAnyRole(USER_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/auth/password").hasAnyRole(USER_ROLES)
                        .requestMatchers(HttpMethod.POST, "/api/messages/inbox/*/reply").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/messages/inbox",
                                "/api/messages/inbox/*").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/messages/conversations",
                                "/api/messages/conversations/*").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/messages/recipients").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.POST, "/api/messages").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.POST, "/api/patients").hasAnyRole("DOCTOR", "STAFF")
                        // Search among MediCore's own Patients; the query is in the body, never in the URL.
                        .requestMatchers(HttpMethod.POST, "/api/patients/search").hasAnyRole("DOCTOR", "STAFF")
                        // Import of existing FHIR Patients: discovery search on the FHIR server and registry entry.
                        .requestMatchers(HttpMethod.POST, "/api/patients/import", "/api/patients/import/search")
                                .hasAnyRole("DOCTOR", "STAFF")
                        // The PATIENT's own record; must come before the DOCTOR/STAFF rule for /api/patients/*.
                        .requestMatchers(HttpMethod.GET, "/api/patients/me").hasRole("PATIENT")
                        // Encounters (US-05.05): the PATIENT's own (before the rule below); DOCTOR/STAFF per Patient.
                        .requestMatchers(HttpMethod.GET, "/api/patients/me/observations").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/patients/*/observations").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/patients/*/observations").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/patients/me/encounters").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/patients/*/encounters").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/patients/*/encounters").hasAnyRole("DOCTOR", "STAFF")
                        // Clinical notes (US-05.01): the PATIENT's own (before the rule below); DOCTOR/STAFF per
                        // Patient. Creating additionally requires a linked Practitioner (ClinicalNoteService).
                        .requestMatchers(HttpMethod.GET, "/api/patients/me/notes").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/patients/*/notes").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/patients/*/notes").hasAnyRole("DOCTOR", "STAFF")
                        // Diagnoses (US-05.02): the PATIENT's own (before the rule below); DOCTOR/STAFF per Patient.
                        // Recording additionally requires a linked Practitioner (DiagnosisService).
                        .requestMatchers(HttpMethod.GET, "/api/patients/me/conditions").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/patients/*/conditions").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.POST, "/api/patients/*/conditions").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/organizations").hasAnyRole("DOCTOR", "STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/practitioners").hasAnyRole("DOCTOR", "STAFF")
                        // Journal access: PATIENT status and own request; DOCTOR review (list, count, details,
                        // decisions).
                        .requestMatchers(HttpMethod.GET, "/api/journal-access/me").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.POST, "/api/journal-access/me/request").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.GET, "/api/journal-access-requests", "/api/journal-access-requests/**")
                                .hasRole("DOCTOR")
                        .requestMatchers(HttpMethod.POST, "/api/journal-access-requests/*/approve",
                                "/api/journal-access-requests/*/reject", "/api/journal-access-requests/*/reopen")
                                .hasRole("DOCTOR")
                        // One Patient by ID only; there is no patient list or search (GET /api/patients stays denied).
                        .requestMatchers(HttpMethod.GET, "/api/patients/*").hasAnyRole("DOCTOR", "STAFF")
                        // Name and gender of one registered Patient (US-04.04); never for PATIENT accounts.
                        .requestMatchers(HttpMethod.PATCH, "/api/patients/*").hasAnyRole("DOCTOR", "STAFF")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                // Logout overwrites the token cookie with an expired one. POST only: without CSRF protection Spring
                // would otherwise accept any method.
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/auth/logout"))
                        .addLogoutHandler((request, response, authentication) ->
                                response.addHeader(HttpHeaders.SET_COOKIE, tokens.clearedCookie().toString()))
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .build();
    }
}
