package com.medinexus.medicore.internal;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.medinexus.medicore.security.jwt.UserClaims;

/** The Identity internal API I1–I2 as Messaging calls it (docs/architecture.md §13, NIT-336). */
@WebMvcTest(IdentityInternalController.class)
@Import(SecurityConfig.class)
class IdentityInternalControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserRepository users;
    @MockitoBean
    private AuthenticationService authenticationService;
    @MockitoBean
    private AccountTokenService accountTokens;

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestTokens.tokens().serviceToken("messaging"));
    }

    /** An account with private fields filled in, to prove they never reach the answer. */
    private static User account(long id, Role role, String given, String family) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        user.setGivenName(given);
        user.setFamilyName(family);
        user.setEmail("PRIVATE@example.test");
        user.setPasswordHash("PRIVATE-HASH");
        user.setPersonnummer("PRIVATE-PERSONNUMMER");
        user.setPhoneNumber("PRIVATE-PHONE");
        user.setFhirPractitionerId("PRIVATE-FHIR-ID");
        return user;
    }

    @Test
    void onlyServiceTokensGetIn() throws Exception {
        mvc.perform(get("/internal/users").param("ids", "1")).andExpect(status().isUnauthorized())
                .andExpect(genericError());
        for (Role role : Role.values()) {
            mvc.perform(get("/internal/users").param("roles", "DOCTOR").cookie(TestTokens.cookieFor(
                    new UserClaims(42L, "user@example.test", role, null, null, null, null))))
                    .andExpect(status().isForbidden()).andExpect(genericError());
        }
        verifyNoInteractions(users);
    }

    @Test
    void i1ReturnsKnownAccountsByIdWithOnlyPublicFields() throws Exception {
        when(users.findAllById(List.of(7L, 4L, 9L, 1234L))).thenReturn(List.of(
                account(7L, Role.DOCTOR, null, " "), account(4L, Role.PATIENT, " Ali ", "Khan"),
                account(9L, Role.STAFF, " ", "Brown")));
        mvc.perform(asService(get("/internal/users").param("ids", "7, 4,9,4,,1234")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"users":[{"id":4,"displayName":"Ali Khan","role":"PATIENT"},
                                  {"id":7,"displayName":"Doctor #7","role":"DOCTOR"},
                                  {"id":9,"displayName":"Brown","role":"STAFF"}]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void i1WithAnEmptyListAsksNothing() throws Exception {
        mvc.perform(asService(get("/internal/users").param("ids", "")))
                .andExpect(status().isOk()).andExpect(content().json("{\"users\":[]}", JsonCompareMode.STRICT));
        verifyNoInteractions(users);
    }

    @Test
    void i2ReturnsEveryAccountWithTheRolesSortedByName() throws Exception {
        when(users.findByRoleIn(List.of(Role.DOCTOR, Role.STAFF))).thenReturn(List.of(
                account(2L, Role.DOCTOR, " Zoe ", "Smith"), account(5L, Role.DOCTOR, "Amy", null),
                account(4L, Role.DOCTOR, null, null), account(6L, Role.STAFF, null, "Brown"),
                account(3L, Role.STAFF, "Amy", null)));
        mvc.perform(asService(get("/internal/users").param("roles", "DOCTOR,STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"users":[{"id":5,"displayName":"Amy","role":"DOCTOR"},
                                  {"id":3,"displayName":"Amy","role":"STAFF"},
                                  {"id":6,"displayName":"Brown","role":"STAFF"},
                                  {"id":4,"displayName":"Doctor #4","role":"DOCTOR"},
                                  {"id":2,"displayName":"Zoe Smith","role":"DOCTOR"}]}
                        """, JsonCompareMode.STRICT));
        verify(users).findByRoleIn(List.of(Role.DOCTOR, Role.STAFF));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ids=abc", "ids=0", "ids=-3", "ids=1.5", "roles=", "roles=ADMIN", "roles=doctor",
            "ids=1&roles=DOCTOR", ""})
    void malformedQueriesAre400(String query) throws Exception {
        mvc.perform(asService(get("/internal/users?" + query))).andExpect(status().isBadRequest())
                .andExpect(genericError());
        verifyNoInteractions(users);
    }

    @Test
    void moreThan200IdsIs400() throws Exception {
        String ids = LongStream.rangeClosed(1, IdentityInternalController.MAX_IDS + 1).mapToObj(Long::toString)
                .collect(Collectors.joining(","));
        mvc.perform(asService(get("/internal/users").param("ids", ids))).andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }
}
