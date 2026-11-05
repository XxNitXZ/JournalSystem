package com.medinexus.medicore.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;

@WebMvcTest(AuthenticationController.class)
@Import(SecurityConfig.class)
class AuthenticationControllerTest {

    private static final String LOGIN = """
            {"email":"user@example.com","password":"a secure password"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService service;

    @ParameterizedTest
    @EnumSource(Role.class)
    void loginPersistsSafePrincipalAndRotatesSessionId(Role role) throws Exception {
        UserDto user = new UserDto(42L, "user@example.com", role);
        when(service.authenticate("user@example.com", "a secure password")).thenReturn(user);
        MockHttpSession session = new MockHttpSession();
        String oldId = session.getId();
        String body = """
                {"id":42,"email":"user@example.com","role":"%s"}
                """.formatted(role);

        mvc.perform(post("/api/auth/login").session(session).with(request -> {
                    request.setRequestedSessionId(oldId);
                    request.setRequestedSessionIdValid(true);
                    return request;
                }).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(content().json(body, JsonCompareMode.STRICT));

        assertThat(session.getId()).isNotEqualTo(oldId);
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(context.getAuthentication().getPrincipal()).isEqualTo(user);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(content().json(body, JsonCompareMode.STRICT));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginCreatesSessionWhenNeeded() throws Exception {
        when(service.authenticate("user@example.com", "a secure password"))
                .thenReturn(new UserDto(42L, "user@example.com", Role.PATIENT));
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Invalid email or password", "Internal sensitive details"})
    void failedCredentialsHaveGenericResponseAndNoAuthentication(String message) throws Exception {
        when(service.authenticate("user@example.com", "a secure password"))
                .thenThrow(new IllegalArgumentException(message));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void missingCredentialsAreRejected() throws Exception {
        when(service.authenticate("", null)).thenThrow(new IllegalArgumentException("Invalid email or password"));
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiresValidCsrf() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void csrfEndpointSupportsRealTokenRoundTripAndRefreshAfterLogin() throws Exception {
        MvcResult bootstrap = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) bootstrap.getRequest().getSession(false);
        // Read the actual masked token returned to the browser, rather than using a test token.
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        var json = mapper.readTree(bootstrap.getResponse().getContentAsString());
        String token = json.get("token").asString();
        String headerName = json.get("headerName").asString();
        when(service.authenticate("user@example.com", "a secure password"))
                .thenReturn(new UserDto(42L, "user@example.com", Role.PATIENT));

        mvc.perform(post("/api/auth/login").session(session).header(headerName, token)
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).header(headerName, token))
                .andExpect(status().isForbidden());
        MvcResult refreshed = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        String freshToken = mapper.readTree(refreshed.getResponse().getContentAsString()).get("token").asString();
        mvc.perform(post("/api/auth/logout").session(session).header(headerName, freshToken))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logoutInvalidatesSessionAndOldCookieCannotRestoreAuthentication() throws Exception {
        when(service.authenticate("user@example.com", "a secure password"))
                .thenReturn(new UserDto(42L, "user@example.com", Role.PATIENT));
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String oldId = session.getId();
        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie("JSESSIONID", oldId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void credentialedPreflightAllowsCsrfHeaderOnlyForConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/auth/login").header("Origin", "http://unrelated.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
