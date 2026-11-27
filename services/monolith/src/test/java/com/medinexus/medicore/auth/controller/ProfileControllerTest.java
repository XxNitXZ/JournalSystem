package com.medinexus.medicore.auth.controller;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.apiError;
import static com.medinexus.medicore.security.jwt.TestTokens.tokenCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.ProfileService;
import com.medinexus.medicore.config.PasswordEncoderConfig;
import com.medinexus.medicore.config.SecurityConfig;

import jakarta.servlet.http.Cookie;

/**
 * GET/PATCH /api/auth/profile and POST /api/auth/password with the real security configuration, a real login
 * cookie and the real {@link ProfileService}; only the repository is mocked. Fictional data only.
 */
@WebMvcTest({ProfileController.class, AuthenticationController.class})
@Import({SecurityConfig.class, ProfileService.class, PasswordEncoderConfig.class, AccountTokenService.class})
class ProfileControllerTest {

    private static final String PASSWORD = "a secure password";
    private static final String LOGIN = "{\"email\":\"anna@example.test\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private UserRepository repository;

    @Autowired
    private JwtDecoder decoder;

    private User account;

    @BeforeEach
    void storedAccount() {
        account = new User();
        ReflectionTestUtils.setField(account, "id", 7L);
        account.setEmail("anna@example.test");
        account.setPasswordHash(new BCryptPasswordEncoder(4).encode(PASSWORD));
        account.setRole(Role.PATIENT);
        account.setGivenName("Anna");
        account.setFamilyName("Testsson");
        account.setPersonnummer("200412201971");
        when(repository.findById(7L)).thenReturn(Optional.of(account));
        // Like the database: a changed e-mail is what the next read of the account returns.
        when(repository.updateEmail(eq(7L), anyString())).thenAnswer(call -> {
            account.setEmail(call.getArgument(1));
            return 1;
        });
        when(repository.updatePhoneNumber(eq(7L), any())).thenReturn(1);
        when(repository.updatePasswordHash(eq(7L), anyString())).thenReturn(1);
    }

    /** Signs in through the real login endpoint and returns the token cookie. */
    private Cookie signIn(Role role) throws Exception {
        account.setRole(role);
        when(authenticationService.authenticate("anna@example.test", PASSWORD))
                .thenReturn(new UserDto(7L, "anna@example.test", role, "Anna", "Testsson"));
        return tokenCookie(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN)).andExpect(status().isOk()).andReturn());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleReadsItsOwnAccountWithTheReadOnlyIdentity(Role role) throws Exception {
        Cookie session = signIn(role);
        mvc.perform(get("/api/auth/profile").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"givenName":"Anna","familyName":"Testsson","personnummer":"200412201971",
                         "email":"anna@example.test","phoneNumber":null,"role":"%s"}
                        """.formatted(role), JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"givenName", "familyName", "personnummer", "role", "id", "fhirPatientId"})
    void identityFieldsCannotBeChanged(String field) throws Exception {
        Cookie session = signIn(Role.DOCTOR);
        mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":\"Changed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("FIELD_NOT_EDITABLE"));
        verify(repository, never()).updateEmail(anyLong(), anyString());
        verify(repository, never()).updatePhoneNumber(anyLong(), any());
    }

    @Test
    void theEmailChangeRenewsTheCookieAndMeShowsTheNewAddressAtOnce() throws Exception {
        Cookie session = signIn(Role.PATIENT);
        Cookie renewed = tokenCookie(mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna.new@example.test\",\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"email\":\"anna.new@example.test\",\"givenName\":\"Anna\"}"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))))
                .andReturn());
        verify(repository).updateEmail(7L, "anna.new@example.test");
        // The renewed token carries the new address; the role is unchanged.
        assertThat(decoder.decode(renewed.getValue()).getClaimAsString("email")).isEqualTo("anna.new@example.test");
        assertThat(decoder.decode(renewed.getValue()).getClaimAsString("role")).isEqualTo("PATIENT");

        mvc.perform(get("/api/auth/me").cookie(renewed))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":7,"email":"anna.new@example.test","role":"PATIENT","givenName":"Anna","familyName":"Testsson"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void wrongPasswordsAndTakenEmailsAreRejectedWithCodes() throws Exception {
        Cookie session = signIn(Role.STAFF);
        mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.test\",\"currentPassword\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(apiError("CURRENT_PASSWORD_INVALID"));
        when(repository.existsByEmail("taken@example.test")).thenReturn(true);
        mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.test\",\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(apiError("EMAIL_TAKEN"));
        mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("PHONE_INVALID"));
        mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{")).andExpect(status().isBadRequest());
        verify(repository, never()).updateEmail(anyLong(), anyString());
        // Nothing was changed: the account still has the old address.
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(content().json("{\"email\":\"anna@example.test\"}"));
    }

    @Test
    void thePhoneNumberIsSavedNormalized() throws Exception {
        Cookie session = signIn(Role.DOCTOR);
        mvc.perform(patch("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+46 70-123 45 67\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"phoneNumber\":\"+46701234567\"}"));
        verify(repository).updatePhoneNumber(7L, "+46701234567");
    }

    @Test
    void thePasswordChangesWithTheCurrentPasswordAndNothingIsEchoed() throws Exception {
        Cookie session = signIn(Role.PATIENT);
        mvc.perform(post("/api/auth/password").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"New-Passw0rd\",\"confirmPassword\":\"New-Passw0rd\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(repository).updatePasswordHash(eq(7L), org.mockito.ArgumentMatchers.startsWith("$2"));

        mvc.perform(post("/api/auth/password").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"New-Passw0rd\","
                                + "\"confirmPassword\":\"New-Passw0rd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(apiError("CURRENT_PASSWORD_INVALID"));
        mvc.perform(post("/api/auth/password").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"New-Passw0rd\",\"confirmPassword\":\"Other\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(apiError("PASSWORD_MISMATCH"));
        // The sign-in survives a password change (stateless tokens stay valid until they expire).
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void anonymousRequestsAndOtherMethodsAreRejected() throws Exception {
        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/auth/profile").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"0701234567\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());

        Cookie session = signIn(Role.STAFF);
        mvc.perform(put("/api/auth/profile").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verify(repository, never()).updatePhoneNumber(anyLong(), any());
        verify(repository, never()).updatePasswordHash(anyLong(), anyString());
    }
}
