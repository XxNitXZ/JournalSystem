package com.medinexus.medicore.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.config.SecurityConfig;

@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    @MockitoBean
    private AuthenticationService authenticationService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationService registrationService;

    @ParameterizedTest
    @EnumSource(Role.class)
    void createsSelectedRoleAndExposesOnlyResponseFields(Role role) throws Exception {
        UserDto user = new UserDto(42L, "patient@example.com", role);
        when(registrationService.register("patient@example.com", "a secure password", role))
                .thenReturn(user);

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"%s"}
                                """.formatted(role)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"id":42,"email":"patient@example.com","role":"%s"}
                        """.formatted(role), JsonCompareMode.STRICT));

        verify(registrationService).register("patient@example.com", "a secure password", role);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\":\"PATIENT\"}",
            "{\"email\":\"invalid\",\"password\":\"password\",\"role\":\"PATIENT\"}",
            "{\"email\":\"patient@example.com\",\"password\":\"\",\"role\":\"PATIENT\"}",
            "{\"email\":\"patient@example.com\",\"role\":\"PATIENT\"}"
    })
    void serviceValidationErrorsReturnBadRequest(String body) throws Exception {
        when(registrationService.register(any(), any(), eq(Role.PATIENT)))
                .thenThrow(new IllegalArgumentException("Invalid registration data"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(registrationService).register(any(), any(), eq(Role.PATIENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{invalid"})
    void unreadableRequestReturnsBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"role\":null"})
    void missingRoleReturnsBadRequestThroughServiceValidation(String roleField) throws Exception {
        when(registrationService.register("patient@example.com", "a secure password", null))
                .thenThrow(new IllegalArgumentException("A role is required"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password"%s}
                                """.formatted(roleField)))
                .andExpect(status().isBadRequest());

        verify(registrationService).register("patient@example.com", "a secure password", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "patient", ""})
    void invalidRoleReturnsBadRequestBeforeCallingService(String role) throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"%s"}
                                """.formatted(role)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    @Test
    void registrationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"PATIENT"}
                                """))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registrationService);
    }

    @Test
    void duplicateEmailReturnsConflictWithoutExposingExceptionDetails() throws Exception {
        when(registrationService.register("patient@example.com", "a secure password", Role.PATIENT))
                .thenThrow(new DuplicateKeyException("Internal database details"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"PATIENT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));
    }

}
