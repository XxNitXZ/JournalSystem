package com.medinexus.medicore.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.service.AuthenticationService;

import jakarta.servlet.DispatcherType;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public HttpSessionCsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository csrfRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfRepository)));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
            HttpSessionCsrfTokenRepository csrfRepository) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health",
                                "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        // The signed-in user's own account: read, change e-mail/phone, change password.
                        .requestMatchers(HttpMethod.GET, "/api/auth/profile").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/profile").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/messages/recipients").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.POST, "/api/messages").hasRole("PATIENT")
                        .requestMatchers(HttpMethod.POST, "/api/patients").hasAnyRole("DOCTOR", "STAFF")
                        // Search among MediCore's own Patients; the query is in the body, never in the URL.
                        .requestMatchers(HttpMethod.POST, "/api/patients/search").hasAnyRole("DOCTOR", "STAFF")
                        // The PATIENT's own record; must come before the DOCTOR/STAFF rule for /api/patients/*.
                        .requestMatchers(HttpMethod.GET, "/api/patients/me").hasRole("PATIENT")
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
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .build();
    }
}
