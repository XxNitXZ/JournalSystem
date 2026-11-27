package com.medinexus.medicore.auth.controller;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.apiError;
import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static com.medinexus.medicore.auth.TestAccounts.account;
import static com.medinexus.medicore.security.jwt.TestTokens.tokenCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import org.springframework.test.web.servlet.MvcResult;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.RegistrationService;
import com.medinexus.medicore.config.SecurityConfig;

@WebMvcTest({RegistrationController.class, AuthenticationController.class})
@Import({SecurityConfig.class, AccountTokenService.class})
@ExtendWith(OutputCaptureExtension.class)
class RegistrationControllerTest {

    @MockitoBean
    private AuthenticationService authenticationService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationService registrationService;

    @MockitoBean
    private UserRepository users;

    @Autowired
    private JwtDecoder decoder;

    @ParameterizedTest
    @EnumSource(Role.class)
    void createsSelectedRoleAndExposesOnlyResponseFields(Role role) throws Exception {
        UserDto user = new UserDto(42L, "patient@example.com", role, "Anna", "Testsson");
        when(registrationService.register("patient@example.com", "a secure password", role, "Anna", "Testsson", "182003151236", null))
                .thenReturn(user);

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"%s","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"}
                                """.formatted(role)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"id":42,"email":"patient@example.com","role":"%s","givenName":"Anna","familyName":"Testsson"}
                        """.formatted(role), JsonCompareMode.STRICT));

        verify(registrationService).register("patient@example.com", "a secure password", role, "Anna", "Testsson", "182003151236", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\":\"PATIENT\"}",
            "{\"email\":\"invalid\",\"password\":\"password\",\"role\":\"PATIENT\"}",
            "{\"email\":\"patient@example.com\",\"password\":\"\",\"role\":\"PATIENT\"}",
            "{\"email\":\"patient@example.com\",\"role\":\"PATIENT\"}"
    })
    void serviceValidationErrorsReturnBadRequest(String body) throws Exception {
        when(registrationService.register(any(), any(), eq(Role.PATIENT), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid registration data"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(registrationService).register(any(), any(), eq(Role.PATIENT), any(), any(), any(), any());
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
        when(registrationService.register("patient@example.com", "a secure password", null, "Anna", "Testsson", "182003151236", null))
                .thenThrow(new IllegalArgumentException("A role is required"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"%s}
                                """.formatted(roleField)))
                .andExpect(status().isBadRequest());

        verify(registrationService).register("patient@example.com", "a secure password", null, "Anna", "Testsson", "182003151236", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "patient", ""})
    void invalidRoleReturnsBadRequestBeforeCallingService(String role) throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"%s","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"}
                                """.formatted(role)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(registrationService);
    }

    @Test
    void duplicateEmailReturnsConflictWithoutExposingExceptionDetails() throws Exception {
        when(registrationService.register("patient@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson", "182003151236", null))
                .thenThrow(new DuplicateKeyException("Internal database details"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"patient@example.com","password":"a secure password","role":"PATIENT","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(genericError());
    }

    // --- Automatic sign-in after registration (PATIENT only) ---

    private static final String PASSWORD = "a secure password";

    private static String body(Role role) {
        return """
                {"email":"new@example.com","password":"a secure password","role":"%s","givenName":"Anna","familyName":"Testsson","personnummer":"182003151236"}
                """.formatted(role);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String body) {
        return post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void patientRegistrationSignsTheNewUserInWithTheSameCookieAsLogin() throws Exception {
        UserDto user = new UserDto(42L, "new@example.com", Role.PATIENT, "Anna", "Testsson");
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null)).thenReturn(user);
        when(authenticationService.authenticate("new@example.com", PASSWORD)).thenReturn(user);
        when(users.findById(42L)).thenReturn(Optional.of(account(42L, "new@example.com", Role.PATIENT, "Anna", "Testsson")));

        MvcResult result = mockMvc.perform(register(body(Role.PATIENT)))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":42,"email":"new@example.com","role":"PATIENT","givenName":"Anna","familyName":"Testsson"}
                        """, JsonCompareMode.STRICT))
                .andReturn();

        Jwt token = decoder.decode(tokenCookie(result).getValue());
        assertThat(token.getSubject()).isEqualTo("42");
        assertThat(token.getClaimAsString("role")).isEqualTo("PATIENT");
        assertThat(result.getRequest().getSession(false)).as("no HTTP session").isNull();
        mockMvc.perform(get("/api/auth/me").cookie(tokenCookie(result)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":42,"email":"new@example.com","role":"PATIENT","givenName":"Anna","familyName":"Testsson"}
                        """, JsonCompareMode.STRICT));
        verify(authenticationService).authenticate("new@example.com", PASSWORD);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void clinicalRolesAreCreatedButNeverSignedInAutomatically(Role role) throws Exception {
        when(registrationService.register("new@example.com", PASSWORD, role, "Anna", "Testsson", "182003151236", null))
                .thenReturn(new UserDto(43L, "new@example.com", role));
        MvcResult result = mockMvc.perform(register(body(role))).andExpect(status().isCreated()).andReturn();

        assertThat(tokenCookie(result)).isNull();
        verifyNoInteractions(authenticationService);
    }

    @Test
    void failedRegistrationsNeverSignAnybodyIn() throws Exception {
        // Even when the submitted credentials belong to an existing account.
        when(authenticationService.authenticate("new@example.com", PASSWORD))
                .thenReturn(new UserDto(7L, "new@example.com", Role.PATIENT));
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null))
                .thenThrow(new DuplicateKeyException("Email is already registered"))
                .thenThrow(new IllegalArgumentException("Invalid registration data"));
        assertThat(tokenCookie(mockMvc.perform(register(body(Role.PATIENT))).andExpect(status().isConflict())
                .andReturn())).isNull();
        assertThat(tokenCookie(mockMvc.perform(register(body(Role.PATIENT))).andExpect(status().isBadRequest())
                .andReturn())).isNull();
        assertThat(tokenCookie(mockMvc.perform(register("{invalid")).andExpect(status().isBadRequest())
                .andReturn())).isNull();
        verify(authenticationService, never()).authenticate(any(), any());
    }

    @Test
    void aFailedAutomaticSignInStillReportsTheCreatedAccountWithoutACookie(CapturedOutput output) throws Exception {
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null))
                .thenReturn(new UserDto(42L, "new@example.com", Role.PATIENT, "Anna", "Testsson"));
        when(authenticationService.authenticate("new@example.com", PASSWORD))
                .thenThrow(new IllegalArgumentException("Invalid email or password"));
        MvcResult result = mockMvc.perform(register(body(Role.PATIENT))).andExpect(status().isCreated()).andReturn();

        assertThat(tokenCookie(result)).isNull();
        assertThat(output.getAll()).contains("Automatic sign-in after registration failed: accountId=42");
        assertThat(output.getAll().contains(PASSWORD)).as("log contains the password").isFalse();
        assertThat(output.getAll().contains("new@example.com")).as("log contains the e-mail").isFalse();
    }

    @Test
    void aTokenForAnotherAccountIsNeverIssued() throws Exception {
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null))
                .thenReturn(new UserDto(42L, "new@example.com", Role.PATIENT, "Anna", "Testsson"));
        when(authenticationService.authenticate("new@example.com", PASSWORD))
                .thenReturn(new UserDto(99L, "new@example.com", Role.PATIENT));
        MvcResult result = mockMvc.perform(register(body(Role.PATIENT))).andExpect(status().isCreated()).andReturn();

        assertThat(tokenCookie(result)).isNull();
        verify(users, never()).findById(any());
    }

    @Test
    void anAlreadySignedInCallerKeepsItsOwnCookie() throws Exception {
        UserDto staff = new UserDto(5L, "staff@example.com", Role.STAFF);
        when(registrationService.register("new@example.com", PASSWORD, Role.PATIENT, "Anna", "Testsson", "182003151236", null))
                .thenReturn(new UserDto(42L, "new@example.com", Role.PATIENT, "Anna", "Testsson"));

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .with(authentication(UsernamePasswordAuthenticationToken.authenticated(staff, null,
                                List.of(new SimpleGrantedAuthority("ROLE_STAFF")))))
                        .contentType(MediaType.APPLICATION_JSON).content(body(Role.PATIENT)))
                .andExpect(status().isCreated()).andReturn();

        assertThat(tokenCookie(result)).isNull();
        verifyNoInteractions(authenticationService);
    }

    @Test
    void aDuplicatePersonnummerIsAConflictWithACodeAndNoCookie() throws Exception {
        when(registrationService.register(any(), any(), any(), any(), any(), any(), any())).thenThrow(
                new com.medinexus.medicore.auth.service.PersonnummerAlreadyRegisteredException());
        MvcResult result = mockMvc.perform(register(body(Role.PATIENT)))
                .andExpect(status().isConflict())
                .andExpect(apiError("PERSONNUMMER_TAKEN")).andReturn();
        assertThat(tokenCookie(result)).isNull();
        verifyNoInteractions(authenticationService);
    }

    @Test
    void thePersonnummerIsPassedToTheServiceAndNotEchoed() throws Exception {
        when(registrationService.register("new@example.com", PASSWORD, Role.DOCTOR, "Anna", "Testsson", "182003151236", null))
                .thenReturn(new UserDto(43L, "new@example.com", Role.DOCTOR, "Anna", "Testsson"));
        mockMvc.perform(register(body(Role.DOCTOR)))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("182003151236"))));
    }

    @Test
    void theOptionalPhoneNumberIsPassedToTheServiceAndNotEchoed() throws Exception {
        when(registrationService.register("new@example.com", PASSWORD, Role.STAFF, "Anna", "Testsson", "182003151236",
                "+46 70 123 45 67")).thenReturn(new UserDto(44L, "new@example.com", Role.STAFF, "Anna", "Testsson"));
        mockMvc.perform(register(body(Role.STAFF).replace("}",
                        ",\"phoneNumber\":\"+46 70 123 45 67\"}")))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("70 123"))));
        verify(registrationService).register("new@example.com", PASSWORD, Role.STAFF, "Anna", "Testsson",
                "182003151236", "+46 70 123 45 67");
    }

}
