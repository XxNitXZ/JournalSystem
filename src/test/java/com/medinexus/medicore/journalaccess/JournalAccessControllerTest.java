package com.medinexus.medicore.journalaccess;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestDetail;
import com.medinexus.medicore.journalaccess.dto.JournalAccessRequestSummary;
import com.medinexus.medicore.journalaccess.dto.JournalAccessStatusDto;

/** Journal access endpoints: route security, CSRF and status mapping. Fictional personnummer only. */
@WebMvcTest(JournalAccessController.class)
@Import(SecurityConfig.class)
class JournalAccessControllerTest {

    private static final Instant CREATED = Instant.parse("2026-11-09T09:00:00Z");
    private static final JournalAccessRequestSummary SUMMARY = new JournalAccessRequestSummary(5L, "Ali", "Khan",
            "182003151236", "ali@example.test", JournalAccessStatus.PENDING, CREATED, null);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationService authenticationService;

    @MockitoBean
    private JournalAccessService service;

    private static UserDto user(Role role) {
        return new UserDto(42L, "user@example.test", role);
    }

    private static RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(user(role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    // ---------- PATIENT status ----------

    @Test
    void aPatientReadsTheirOwnStatus() throws Exception {
        when(service.statusFor(user(Role.PATIENT))).thenReturn(new JournalAccessStatusDto("PENDING"));
        mvc.perform(get("/api/journal-access/me").with(as(Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"PENDING\"}", true));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void clinicalRolesHaveNoPatientStatus(Role role) throws Exception {
        mvc.perform(get("/api/journal-access/me").with(as(role))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void aPatientAsksForAccessWithACsrfTokenAndTheBodyIsIgnored() throws Exception {
        when(service.requestAccess(user(Role.PATIENT))).thenReturn(new JournalAccessStatusDto("AWAITING_RECORD"));
        // Nothing from the client selects the account or the record.
        mvc.perform(post("/api/journal-access/me/request").with(csrf()).with(as(Role.PATIENT))
                        .contentType("application/json")
                        .content("{\"userId\":99,\"personnummer\":\"182511044568\",\"fhirPatientId\":\"syn-other\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"AWAITING_RECORD\"}", true));
        verify(service).requestAccess(user(Role.PATIENT));
    }

    @Test
    void askingForAccessNeedsACsrfTokenAndAPatientAccount() throws Exception {
        mvc.perform(post("/api/journal-access/me/request").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        for (Role role : List.of(Role.DOCTOR, Role.STAFF)) {
            mvc.perform(post("/api/journal-access/me/request").with(csrf()).with(as(role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/journal-access/me/request").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/journal-access/me/request").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void refusedRequestsAreConflictsWithACode() throws Exception {
        when(service.requestAccess(any())).thenThrow(new JournalAccessConflictException("REQUEST_REJECTED"));
        mvc.perform(post("/api/journal-access/me/request").with(csrf()).with(as(Role.PATIENT)))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"REQUEST_REJECTED\"}", true));
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/journal-access/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/journal-access-requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/journal-access-requests/5/approve").with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // ---------- DOCTOR review ----------

    @Test
    void doctorsListTheRequestsAndCountThePendingOnes() throws Exception {
        when(service.list(user(Role.DOCTOR))).thenReturn(List.of(SUMMARY));
        when(service.pendingCount(user(Role.DOCTOR))).thenReturn(4L);
        mvc.perform(get("/api/journal-access-requests").with(as(Role.DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":5,"givenName":"Ali","familyName":"Khan","personnummer":"182003151236",
                          "email":"ali@example.test","status":"PENDING","createdAt":"2026-11-09T09:00:00Z",
                          "reviewedAt":null}]
                        """, true));
        mvc.perform(get("/api/journal-access-requests/pending/count").with(as(Role.DOCTOR)))
                .andExpect(status().isOk()).andExpect(content().json("{\"pending\":4}", true));
    }

    @Test
    void theDetailNeverContainsTheFhirId() throws Exception {
        when(service.detail(user(Role.DOCTOR), 5L)).thenReturn(new JournalAccessRequestDetail(5L,
                JournalAccessStatus.PENDING, CREATED, null,
                new JournalAccessRequestDetail.Account("Ali", "Khan", "182003151236", "ali@example.test"),
                new JournalAccessRequestDetail.PatientRecord(List.of("Ali"), "Khan", "182003151236", "male",
                        "1820-03-15"),
                JournalAccessRequestDetail.RecordStatus.AVAILABLE, true));
        mvc.perform(get("/api/journal-access-requests/5").with(as(Role.DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":5,"status":"PENDING","recordStatus":"AVAILABLE","personnummerMatches":true,
                         "account":{"givenName":"Ali","familyName":"Khan","personnummer":"182003151236",
                                    "email":"ali@example.test"},
                         "record":{"givenNames":["Ali"],"familyName":"Khan","personnummer":"182003151236",
                                   "gender":"male","birthDate":"1820-03-15"}}
                        """))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("fhirPatientId"))));
    }

    @Test
    void doctorsApproveRejectAndReopenWithACsrfToken() throws Exception {
        when(service.approve(user(Role.DOCTOR), 5L)).thenReturn(SUMMARY);
        when(service.reject(user(Role.DOCTOR), 6L)).thenReturn(SUMMARY);
        when(service.reopen(user(Role.DOCTOR), 7L)).thenReturn(SUMMARY);
        mvc.perform(post("/api/journal-access-requests/5/approve").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isOk()).andExpect(content().json("{\"id\":5}"));
        mvc.perform(post("/api/journal-access-requests/6/reject").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/journal-access-requests/7/reopen").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isOk());
        verify(service).approve(user(Role.DOCTOR), 5L);
        verify(service).reject(user(Role.DOCTOR), 6L);
        verify(service).reopen(user(Role.DOCTOR), 7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "reopen"})
    void decisionsWithoutCsrfTokenAreForbidden(String action) throws Exception {
        mvc.perform(post("/api/journal-access-requests/5/" + action).with(as(Role.DOCTOR)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"PATIENT", "STAFF"})
    void onlyDoctorsReview(Role role) throws Exception {
        mvc.perform(get("/api/journal-access-requests").with(as(role))).andExpect(status().isForbidden());
        mvc.perform(get("/api/journal-access-requests/pending/count").with(as(role))).andExpect(status().isForbidden());
        mvc.perform(get("/api/journal-access-requests/5").with(as(role))).andExpect(status().isForbidden());
        for (String action : List.of("approve", "reject", "reopen")) {
            mvc.perform(post("/api/journal-access-requests/5/" + action).with(csrf()).with(as(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void otherMethodsAndPathsRemainDenied(Role role) throws Exception {
        mvc.perform(delete("/api/journal-access-requests/5").with(csrf()).with(as(role))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/journal-access-requests/5").with(csrf()).with(as(role))).andExpect(status().isForbidden());
        mvc.perform(post("/api/journal-access-requests").with(csrf()).with(as(role))).andExpect(status().isForbidden());
        mvc.perform(post("/api/journal-access/me").with(csrf()).with(as(role))).andExpect(status().isForbidden());
        mvc.perform(post("/api/journal-access-requests/5/delete").with(csrf()).with(as(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void serviceErrorsAreMappedToStatusCodes() throws Exception {
        when(service.approve(any(), anyLong())).thenThrow(new JournalAccessConflictException("PERSONNUMMER_MISMATCH"));
        mvc.perform(post("/api/journal-access-requests/5/approve").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":\"PERSONNUMMER_MISMATCH\"}", true));

        when(service.reject(any(), anyLong())).thenThrow(new JournalAccessRequestNotFoundException());
        mvc.perform(post("/api/journal-access-requests/5/reject").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isNotFound());

        when(service.reopen(any(), anyLong())).thenThrow(new AccessDeniedException("stale role"));
        mvc.perform(post("/api/journal-access-requests/5/reopen").with(csrf()).with(as(Role.DOCTOR)))
                .andExpect(status().isForbidden());

        when(service.detail(any(), anyLong())).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.UNAVAILABLE, null, List.of(), "ConnectException"));
        mvc.perform(get("/api/journal-access-requests/5").with(as(Role.DOCTOR)))
                .andExpect(status().isServiceUnavailable());

        when(service.list(any())).thenThrow(new FhirServerException("patient-read",
                FhirServerException.Type.INVALID_RESPONSE, null, List.of(), null));
        mvc.perform(get("/api/journal-access-requests").with(as(Role.DOCTOR))).andExpect(status().isBadGateway());

        mvc.perform(get("/api/journal-access-requests/abc").with(as(Role.DOCTOR))).andExpect(status().isBadRequest());
    }
}
