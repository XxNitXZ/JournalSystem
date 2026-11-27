package com.medinexus.medicore.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.messaging.repository.MessageRepository;

@SpringBootTest
@Transactional
class ProfessionalInboxIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MessageRepository messages;
    private MockMvc mvc;
    private Long maja;
    private Long ebba;
    private Long doctor;
    private Long staff;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        maja = account(Role.PATIENT, "Maja");
        ebba = account(Role.PATIENT, "Ebba");
        doctor = account(Role.DOCTOR, "Doctor");
        staff = account(Role.STAFF, null);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void inboxAndThreadAreIsolatedForEachProfessional(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        Long other = role == Role.DOCTOR ? staff : doctor;
        Long first = message(maja, professional, "OWN QUESTION", "2026-01-01T00:00:00Z");
        Long second = message(professional, maja, "OWN REPLY", "2026-01-01T00:00:00Z");
        Long later = message(maja, professional, "LATER", "2026-01-02T00:00:00Z");
        message(maja, other, "OTHER PROFESSIONAL PRIVATE", "2026-01-03T00:00:00Z");
        message(other, maja, "OTHER REPLY PRIVATE", "2026-01-03T00:00:00Z");
        message(ebba, other, "EBBA PRIVATE", "2026-01-03T00:00:00Z");
        mvc.perform(get("/api/messages/inbox").param("professionalId", other.toString()).with(as(professional, role)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"patientId":%d,"displayName":"Maja Synthetic","messageCount":3,"lastMessageAt":"2026-01-02T00:00:00Z"}]
                        """.formatted(maja), org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get("/api/messages/inbox/" + maja).param("professionalId", other.toString()).with(as(professional, role)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"id":%d,"senderId":%d,"recipientId":%d,"content":"OWN QUESTION","createdAt":"2026-01-01T00:00:00Z"},
                         {"id":%d,"senderId":%d,"recipientId":%d,"content":"OWN REPLY","createdAt":"2026-01-01T00:00:00Z"},
                         {"id":%d,"senderId":%d,"recipientId":%d,"content":"LATER","createdAt":"2026-01-02T00:00:00Z"}]
                        """.formatted(first, maja, professional, second, professional, maja, later, maja, professional),
                        org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get("/api/messages/inbox/" + ebba).with(as(professional, role)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void emptyAndOutgoingOnlyConversationsAreNotInboxEntries(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        message(professional, maja, "OUTGOING ONLY", "2026-01-01T00:00:00Z");
        for (String path : List.of("/api/messages/inbox", "/api/messages/inbox/" + maja)) {
            mvc.perform(get(path).with(as(professional, role))).andExpect(status().isOk()).andExpect(content().json("[]"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void inboxOrderingAndPatientFallbackNameAreDeterministic(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        Long unnamed = account(Role.PATIENT, null);
        message(maja, professional, "Older", "2026-01-01T00:00:00Z");
        message(ebba, professional, "Newer", "2026-01-02T00:00:00Z");
        message(unnamed, professional, "Same time", "2026-01-02T00:00:00Z");
        assertThat(messages.findInbox(professional)).extracting(summary -> summary.getPatient().getId())
                .containsExactly(ebba, unnamed, maja);
        mvc.perform(get("/api/messages/inbox").with(as(professional, role)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].displayName")
                        .value("Patient #" + unnamed));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsInvalidUnknownAndNonPatientTargets(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        for (String id : List.of("0", "-1", "invalid", "1.5")) {
            mvc.perform(get("/api/messages/inbox/" + id).with(as(professional, role))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/messages/inbox/9223372036854775807").with(as(professional, role))).andExpect(status().isNotFound());
        for (Long id : List.of(doctor, staff)) {
            mvc.perform(get("/api/messages/inbox/" + id).with(as(professional, role))).andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsStaleAndDeletedProfessionalAccounts(Role role) throws Exception {
        for (String path : List.of("/api/messages/inbox", "/api/messages/inbox/" + maja)) {
            mvc.perform(get(path).with(as(ebba, role))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(as(-1L, role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymousAndPatientCannotReadInbox() throws Exception {
        for (String path : List.of("/api/messages/inbox", "/api/messages/inbox/" + maja)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(as(maja, Role.PATIENT))).andExpect(status().isForbidden());
        }
    }

    private Long account(Role role, String given) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, role, given_name, family_name)
                VALUES (?, 'PRIVATE-HASH', ?, ?, ?) RETURNING id
                """, Long.class, UUID.randomUUID() + "@example.test", role.name(), given, given == null ? null : "Synthetic");
    }

    private Long message(Long sender, Long recipient, String text, String time) {
        return jdbc.queryForObject("INSERT INTO messages (sender_id, recipient_id, content, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, sender, recipient, text, java.sql.Timestamp.from(Instant.parse(time)));
    }

    private RequestPostProcessor as(Long id, Role sessionRole) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new UserDto(id, "synthetic@example.test", sessionRole),
                null, List.of(new SimpleGrantedAuthority("ROLE_" + sessionRole.name()))));
    }
}
