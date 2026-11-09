package com.medinexus.medicore.auth.controller;

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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.auth.service.ProfileService;
import com.medinexus.medicore.config.PasswordEncoderConfig;
import com.medinexus.medicore.config.SecurityConfig;

/**
 * GET/PATCH /api/auth/profile and POST /api/auth/password with the real security configuration, a real login
 * session and the real {@link ProfileService}; only the repository is mocked. Fictional data only.
 */
@WebMvcTest({ProfileController.class, AuthenticationController.class})
@Import({SecurityConfig.class, ProfileService.class, PasswordEncoderConfig.class})
class ProfileControllerTest {

    private static final String PASSWORD = "a secure password";
    private static final String LOGIN = "{\"email\":\"anna@example.test\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private UserRepository repository;

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
        when(repository.updateEmail(eq(7L), anyString())).thenReturn(1);
        when(repository.updatePhoneNumber(eq(7L), any())).thenReturn(1);
        when(repository.updatePasswordHash(eq(7L), anyString())).thenReturn(1);
    }

    /** Signs in through the real login endpoint and returns the session. */
    private MockHttpSession signIn(Role role) throws Exception {
        account.setRole(role);
        when(authenticationService.authenticate("anna@example.test", PASSWORD))
                .thenReturn(new UserDto(7L, "anna@example.test", role, "Anna", "Testsson"));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN)).andExpect(status().isOk());
        return session;
    }

    private static SecurityContext storedContext(MockHttpSession session) {
        return (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleReadsItsOwnAccountWithTheReadOnlyIdentity(Role role) throws Exception {
        MockHttpSession session = signIn(role);
        mvc.perform(get("/api/auth/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"givenName":"Anna","familyName":"Testsson","personnummer":"200412201971",
                         "email":"anna@example.test","phoneNumber":null,"role":"%s"}
                        """.formatted(role), JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"givenName", "familyName", "personnummer", "role", "id", "fhirPatientId"})
    void identityFieldsCannotBeChanged(String field) throws Exception {
        MockHttpSession session = signIn(Role.DOCTOR);
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":\"Changed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"FIELD_NOT_EDITABLE\"}", JsonCompareMode.STRICT));
        verify(repository, never()).updateEmail(anyLong(), anyString());
        verify(repository, never()).updatePhoneNumber(anyLong(), any());
    }

    @Test
    void theEmailChangeKeepsTheSessionAndMeShowsTheNewAddressAtOnce() throws Exception {
        MockHttpSession session = signIn(Role.PATIENT);
        String sessionId = session.getId();
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anna.new@example.test\",\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"email\":\"anna.new@example.test\",\"givenName\":\"Anna\"}"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(PASSWORD))));
        verify(repository).updateEmail(7L, "anna.new@example.test");

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":7,"email":"anna.new@example.test","role":"PATIENT","givenName":"Anna","familyName":"Testsson"}
                        """, JsonCompareMode.STRICT));
        assertThat(session.getId()).isEqualTo(sessionId);
        assertThat(storedContext(session).getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_PATIENT");
        assertThat(storedContext(session).getAuthentication().getCredentials()).isNull();
    }

    @Test
    void wrongPasswordsAndTakenEmailsAreRejectedWithCodes() throws Exception {
        MockHttpSession session = signIn(Role.STAFF);
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.test\",\"currentPassword\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"CURRENT_PASSWORD_INVALID\"}", JsonCompareMode.STRICT));
        when(repository.existsByEmail("taken@example.test")).thenReturn(true);
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.test\",\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"EMAIL_TAKEN\"}", JsonCompareMode.STRICT));
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"PHONE_INVALID\"}", JsonCompareMode.STRICT));
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{")).andExpect(status().isBadRequest());
        verify(repository, never()).updateEmail(anyLong(), anyString());
        // The session still has the old address.
        mvc.perform(get("/api/auth/me").session(session)).andExpect(content().json("{\"email\":\"anna@example.test\"}"));
    }

    @Test
    void thePhoneNumberIsSavedNormalized() throws Exception {
        MockHttpSession session = signIn(Role.DOCTOR);
        mvc.perform(patch("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+46 70-123 45 67\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"phoneNumber\":\"+46701234567\"}"));
        verify(repository).updatePhoneNumber(7L, "+46701234567");
    }

    @Test
    void thePasswordChangesWithTheCurrentPasswordAndNothingIsEchoed() throws Exception {
        MockHttpSession session = signIn(Role.PATIENT);
        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"New-Passw0rd\",\"confirmPassword\":\"New-Passw0rd\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(repository).updatePasswordHash(eq(7L), org.mockito.ArgumentMatchers.startsWith("$2"));

        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"New-Passw0rd\","
                                + "\"confirmPassword\":\"New-Passw0rd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"CURRENT_PASSWORD_INVALID\"}", JsonCompareMode.STRICT));
        mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"New-Passw0rd\",\"confirmPassword\":\"Other\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"PASSWORD_MISMATCH\"}", JsonCompareMode.STRICT));
        // The session survives a password change.
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void anonymousRequestsAndRequestsWithoutCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/auth/profile").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"0701234567\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());

        MockHttpSession session = signIn(Role.STAFF);
        mvc.perform(patch("/api/auth/profile").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"0701234567\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/profile").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verify(repository, never()).updatePhoneNumber(anyLong(), any());
        verify(repository, never()).updatePasswordHash(anyLong(), anyString());
    }
}
