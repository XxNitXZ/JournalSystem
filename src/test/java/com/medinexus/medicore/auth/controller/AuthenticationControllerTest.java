package com.medinexus.medicore.auth.controller;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.apiError;
import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static com.medinexus.medicore.security.jwt.TestTokens.account;
import static com.medinexus.medicore.security.jwt.TestTokens.tokenCookie;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;

import jakarta.servlet.http.Cookie;

/** Login, {@code /me} and logout with the signed token cookie (NIT-327). */
@WebMvcTest(AuthenticationController.class)
@Import({SecurityConfig.class, AccountTokenService.class})
class AuthenticationControllerTest {

    private static final String LOGIN = """
            {"email":"user@example.com","password":"a secure password"}
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private AuthenticationService service;

    @MockitoBean
    private UserRepository users;

    private User stored(Role role, String givenName, String familyName) {
        User account = account(42L, "user@example.com", role, givenName, familyName);
        when(users.findById(42L)).thenReturn(Optional.of(account));
        when(service.authenticate("user@example.com", "a secure password"))
                .thenReturn(new UserDto(42L, "user@example.com", role, givenName, familyName));
        return account;
    }

    private Cookie login() throws Exception {
        return tokenCookie(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk()).andReturn());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void loginSetsASignedHttpOnlyStrictCookieThatAuthenticatesMe(Role role) throws Exception {
        stored(role, "Anna", "Testsson");
        String body = """
                {"id":42,"email":"user@example.com","role":"%s","givenName":"Anna","familyName":"Testsson"}
                """.formatted(role);

        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(content().json(body, JsonCompareMode.STRICT))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("medicore_token=").contains("HttpOnly", "SameSite=Strict", "Path=/",
                "Max-Age=1800");
        assertThat(result.getRequest().getSession(false)).as("no HTTP session").isNull();
        Cookie cookie = tokenCookie(result);
        Jwt token = decoder.decode(cookie.getValue());
        assertThat(token.getSubject()).isEqualTo("42");
        assertThat(token.getClaimAsString("role")).isEqualTo(role.name());
        assertThat(token.getClaimAsString("typ")).isEqualTo("user");

        mvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().json(body, JsonCompareMode.STRICT));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theTokenCarriesTheLinkedFhirIdsSoOtherServicesNeedNoLookup() throws Exception {
        stored(Role.PATIENT, "Anna", "Testsson").setFhirPatientId("syn-patient-1");
        Jwt token = decoder.decode(login().getValue());
        assertThat(token.getClaimAsString("fhirPatientId")).isEqualTo("syn-patient-1");
        assertThat(token.hasClaim("fhirPractitionerId")).isFalse();
        assertThat(token.hasClaim("password")).isFalse();
        assertThat(token.hasClaim("personnummer")).isFalse();
    }

    @Test
    void meRenewsTheCookieWithClaimsReadFreshFromTheDatabase() throws Exception {
        // A patient approved for journal access after login gets the new FHIR id without signing in again.
        User account = stored(Role.PATIENT, "Anna", "Testsson");
        Cookie beforeApproval = login();
        assertThat(decoder.decode(beforeApproval.getValue()).hasClaim("fhirPatientId")).isFalse();
        account.setFhirPatientId("syn-patient-1");

        Cookie renewed = tokenCookie(mvc.perform(get("/api/auth/me").cookie(beforeApproval))
                .andExpect(status().isOk()).andReturn());

        assertThat(decoder.decode(renewed.getValue()).getClaimAsString("fhirPatientId")).isEqualTo("syn-patient-1");
    }

    @Test
    void anAccountDeletedAfterLoginIsSignedOutByMe() throws Exception {
        stored(Role.STAFF, "Anna", "Testsson");
        Cookie cookie = login();
        when(users.findById(42L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void anAccountWithoutStoredNamesSignsInWithNullNames() throws Exception {
        // Accounts created before names were stored (V4) keep working; their names are null, never invented.
        stored(Role.STAFF, null, null);
        String body = """
                {"id":42,"email":"user@example.com","role":"STAFF","givenName":null,"familyName":null}
                """;
        Cookie cookie = tokenCookie(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(content().json(body, JsonCompareMode.STRICT)).andReturn());
        mvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().json(body, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Invalid email or password", "Internal sensitive details"})
    void failedCredentialsHaveGenericResponseAndNoCookie(String message) throws Exception {
        when(service.authenticate("user@example.com", "a secure password"))
                .thenThrow(new IllegalArgumentException(message));
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized()).andExpect(genericError()).andReturn();
        assertThat(tokenCookie(result)).isNull();
    }

    @Test
    void missingCredentialsAreRejected() throws Exception {
        when(service.authenticate("", null)).thenThrow(new IllegalArgumentException("Invalid email or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredCookieDoesNotBlockLogin() throws Exception {
        // A stale cookie left in the browser must not turn login into 401.
        stored(Role.PATIENT, "Anna", "Testsson");
        Cookie expired = new Cookie("medicore_token", com.medinexus.medicore.security.jwt.TestTokens
                .tokens(java.time.Instant.now().minusSeconds(3600)).userToken(
                        new com.medinexus.medicore.security.jwt.UserClaims(42L, "user@example.com", Role.PATIENT,
                                null, null, null, null)));
        mvc.perform(post("/api/auth/login").cookie(expired).contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        stored(Role.PATIENT, "Anna", "Testsson");
        Cookie cookie = login();
        MvcResult logout = mvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andReturn();
        assertThat(tokenCookie(logout).getValue()).isEmpty();
        // Logout works without a cookie too, and only with POST.
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/logout").cookie(cookie)).andExpect(status().isForbidden());
    }

    @Test
    void theCsrfEndpointIsATemporaryNoOpForMediPortal() throws Exception {
        // Removed in NIT-338; until then MediPortal fetches it before every POST/PATCH and sends the header.
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"unused\"}"));
    }

    @Test
    void credentialedPreflightIsAllowedOnlyForConfiguredOrigins() throws Exception {
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
