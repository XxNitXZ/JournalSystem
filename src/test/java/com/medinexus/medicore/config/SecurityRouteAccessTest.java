package com.medinexus.medicore.config;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.medinexus.medicore.auth.controller.AuthenticationController;
import com.medinexus.medicore.auth.controller.RegistrationController;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.health.controller.HealthController;

@WebMvcTest({AuthenticationController.class, RegistrationController.class, HealthController.class})
@Import(SecurityConfig.class)
class SecurityRouteAccessTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private RegistrationService registrationService;

    @ParameterizedTest
    @ValueSource(strings = {"/api/health", "/api/auth/csrf"})
    void publicGetEndpointsAllowAnonymousRequests(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void publicRegistrationAndLoginRemainAvailableWithCsrf() throws Exception {
        UserDto user = new UserDto(42L, "user@example.com", Role.PATIENT);
        when(registrationService.register(user.email(), "password", Role.PATIENT)).thenReturn(user);
        when(authenticationService.authenticate(user.email(), "password")).thenReturn(user);
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"password","role":"PATIENT"}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"password"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void meRejectsAnonymousRequests() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void meAllowsEveryAuthenticatedRole(Role role) throws Exception {
        UserDto user = new UserDto(42L, "user@example.com", role);
        mvc.perform(get("/api/auth/me").with(authentication(
                        UsernamePasswordAuthenticationToken.authenticated(user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))))))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":42,"email":"user@example.com","role":"%s"}
                        """.formatted(role)));
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/auth/login", "GET, /api/auth/register", "POST, /api/auth/me",
            "POST, /api/auth/csrf", "POST, /api/health", "POST, /v3/api-docs",
            "POST, /swagger-ui/index.html", "GET, /api/auth/logout",
            "GET, /api/unmatched", "POST, /api/unmatched", "GET, /error"})
    void unmatchedMethodsAndPathsAreDeniedEvenWhenAuthenticated(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(csrf())
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                new UserDto(42L, "user@example.com", Role.STAFF), null,
                                List.of(new SimpleGrantedAuthority("ROLE_STAFF"))))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unmatchedAnonymousRequestCannotReachAnEndpoint() throws Exception {
        mvc.perform(get("/api/unmatched")).andExpect(status().isUnauthorized());
    }
}
