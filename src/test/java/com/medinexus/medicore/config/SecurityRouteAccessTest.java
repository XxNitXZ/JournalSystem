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
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    @MethodSource("deniedRequestsForEveryRole")
    void unmatchedMethodsAndPathsAreDeniedEvenWhenAuthenticated(Role role, String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(csrf())
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                                new UserDto(42L, "user@example.com", role), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))))))
                .andExpect(status().isForbidden());
    }

    private static Stream<Arguments> deniedRequestsForEveryRole() {
        return Arrays.stream(Role.values()).flatMap(role -> Stream.of(
                new String[] {"GET", "/api/auth/login"}, new String[] {"GET", "/api/auth/register"},
                new String[] {"POST", "/api/auth/me"}, new String[] {"POST", "/api/auth/csrf"},
                new String[] {"POST", "/api/health"}, new String[] {"POST", "/v3/api-docs"},
                new String[] {"POST", "/swagger-ui/index.html"}, new String[] {"GET", "/api/auth/logout"},
                new String[] {"GET", "/api/unmatched"}, new String[] {"POST", "/api/unmatched"},
                new String[] {"GET", "/error"})
                .map(request -> Arguments.of(role, request[0], request[1])));
    }

    @Test
    void unmatchedAnonymousRequestCannotReachAnEndpoint() throws Exception {
        mvc.perform(get("/api/unmatched")).andExpect(status().isUnauthorized());
    }
}
