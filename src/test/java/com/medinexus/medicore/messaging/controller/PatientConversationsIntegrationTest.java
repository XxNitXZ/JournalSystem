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
class PatientConversationsIntegrationTest {
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

    @Test
    void ownConversationsAggregateBothDirectionsAndExcludeOtherPatients() throws Exception {
        message(maja, doctor, "Synthetic question", "2026-01-01T00:00:00Z");
        message(doctor, maja, "Synthetic reply", "2026-01-02T00:00:00Z");
        message(maja, staff, "Synthetic staff question", "2026-01-02T00:00:00Z");
        message(ebba, doctor, "PRIVATE EBBA", "2026-01-03T00:00:00Z");
        message(ebba, staff, "PRIVATE EBBA", "2026-01-03T00:00:00Z");
        mvc.perform(get("/api/messages/conversations").with(as(maja, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"recipientId":%d,"displayName":"Doctor Synthetic","role":"DOCTOR","messageCount":2,"lastMessageAt":"2026-01-02T00:00:00Z"},
                         {"recipientId":%d,"displayName":"Staff #%d","role":"STAFF","messageCount":1,"lastMessageAt":"2026-01-02T00:00:00Z"}]
                        """.formatted(doctor, staff, staff), org.springframework.test.json.JsonCompareMode.STRICT));
        assertThat(messages.findConversations(ebba)).allSatisfy(summary -> assertThat(summary.getMessageCount()).isEqualTo(1));
    }

    @Test
    void threadContainsBothDirectionsInTimeThenIdOrderWithoutEbbaMessages() throws Exception {
        Long later = message(doctor, maja, "Later", "2026-01-02T00:00:00Z");
        Long first = message(maja, doctor, "First", "2026-01-01T00:00:00Z");
        Long second = message(doctor, maja, "Second", "2026-01-01T00:00:00Z");
        message(doctor, ebba, "PRIVATE EBBA", "2026-01-01T00:00:00Z");
        message(maja, staff, "OTHER PROFESSIONAL", "2026-01-01T00:00:00Z");
        mvc.perform(get("/api/messages/conversations/" + doctor).with(as(maja, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"id":%d,"senderId":%d,"recipientId":%d,"content":"First","createdAt":"2026-01-01T00:00:00Z"},
                         {"id":%d,"senderId":%d,"recipientId":%d,"content":"Second","createdAt":"2026-01-01T00:00:00Z"},
                         {"id":%d,"senderId":%d,"recipientId":%d,"content":"Later","createdAt":"2026-01-02T00:00:00Z"}]
                        """.formatted(first, maja, doctor, second, doctor, maja, later, doctor, maja),
                        org.springframework.test.json.JsonCompareMode.STRICT));
        assertThat(messages.findThread(ebba, doctor)).extracting(message -> message.getContent()).containsExactly("PRIVATE EBBA");
    }

    @Test
    void listOrdersMostRecentFirst() {
        message(maja, doctor, "Older", "2026-01-01T00:00:00Z");
        message(staff, maja, "Newer", "2026-01-02T00:00:00Z");
        assertThat(messages.findConversations(maja)).extracting(summary -> summary.getProfessional().getId())
                .containsExactly(staff, doctor);
    }

    @Test
    void emptyResultsDoNotLeakAnotherPatientsConversation() throws Exception {
        message(ebba, doctor, "PRIVATE EBBA", "2026-01-01T00:00:00Z");
        for (String path : List.of("/api/messages/conversations", "/api/messages/conversations/" + doctor)) {
            mvc.perform(get(path).with(as(maja, Role.PATIENT)))
                    .andExpect(status().isOk()).andExpect(content().json("[]"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "1.5"})
    void invalidRecipientIsBadRequest(String id) throws Exception {
        mvc.perform(get("/api/messages/conversations/" + id).with(as(maja, Role.PATIENT)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrNonProfessionalRecipientsAreRejected() throws Exception {
        mvc.perform(get("/api/messages/conversations/9223372036854775807").with(as(maja, Role.PATIENT)))
                .andExpect(status().isNotFound());
        for (Long id : List.of(maja, ebba)) {
            mvc.perform(get("/api/messages/conversations/" + id).with(as(maja, Role.PATIENT)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        for (String path : paths()) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void professionalAndStalePatientSessionsAreForbidden(Role role) throws Exception {
        for (String path : paths()) {
            mvc.perform(get(path).with(as(doctor, role))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(as(doctor, Role.PATIENT))).andExpect(status().isForbidden());
        }
    }

    @Test
    void deletedPatientIsForbidden() throws Exception {
        for (String path : paths()) mvc.perform(get(path).with(as(-1L, Role.PATIENT))).andExpect(status().isForbidden());
    }

    private List<String> paths() {
        return List.of("/api/messages/conversations", "/api/messages/conversations/" + doctor);
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
