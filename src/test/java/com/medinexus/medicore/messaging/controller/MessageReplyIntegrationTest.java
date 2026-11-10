package com.medinexus.medicore.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.springframework.http.MediaType;
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
class MessageReplyIntegrationTest {
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
    void replyPersistsAuthenticatedIdentityAndAppearsInBothThreads(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        message(maja, professional, "Question", "2026-01-01T00:00:00Z");
        Instant before = Instant.now().minusMillis(1);
        mvc.perform(post(path(maja)).with(as(professional, role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" Synthetic reply \"}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").isNumber())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.createdAt").isString());
        var thread = messages.findThread(maja, professional);
        assertThat(thread).hasSize(2);
        var reply = thread.get(1);
        assertThat(reply.getSenderId()).isEqualTo(professional);
        assertThat(reply.getRecipientId()).isEqualTo(maja);
        assertThat(reply.getContent()).isEqualTo(" Synthetic reply ");
        assertThat(reply.getCreatedAt()).isBetween(before, Instant.now());
        for (String path : List.of("/api/messages/inbox/" + maja, "/api/messages/conversations/" + professional)) {
            boolean professionalView = path.contains("inbox");
            mvc.perform(get(path).with(as(professionalView ? professional : maja, professionalView ? role : Role.PATIENT)))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].content").value(" Synthetic reply "));
        }
        assertThat(messages.findInbox(professional).get(0).getMessageCount()).isEqualTo(2);
        assertThat(messages.findConversations(maja).get(0).getMessageCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void unrelatedProfessionalAndNewConversationAreForbidden(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        Long other = role == Role.DOCTOR ? staff : doctor;
        message(maja, other, "PRIVATE", "2026-01-01T00:00:00Z");
        rejected(professional, role, maja.toString(), "{\"content\":\"Synthetic\"}", 403);
        rejected(professional, role, ebba.toString(), "{\"content\":\"Synthetic\"}", 403);
        message(professional, ebba, "Outgoing only", "2026-01-01T00:00:00Z");
        rejected(professional, role, ebba.toString(), "{\"content\":\"Synthetic\"}", 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"content\":null}", "{\"content\":42}",
            "{\"content\":\" \"}", "{\"content\":\"\\t\\n\"}",
            "{\"content\":\"text\",\"senderId\":1}", "{\"content\":\"text\",\"professionalId\":1}",
            "{\"content\":\"text\",\"patientId\":1}", "{\"content\":\"text\",\"recipientId\":1}"})
    void invalidContentAndForgedFieldsAreRejected(String body) throws Exception {
        message(maja, doctor, "Question", "2026-01-01T00:00:00Z");
        rejected(doctor, Role.DOCTOR, maja.toString(), body, 400);
    }

    @Test
    void unicodeBoundaryIsEnforcedWithoutTruncation() throws Exception {
        message(maja, doctor, "Question", "2026-01-01T00:00:00Z");
        rejected(doctor, Role.DOCTOR, maja.toString(), "{\"content\":\"" + "😀".repeat(5001) + "\"}", 400);
        mvc.perform(post(path(maja)).with(as(doctor, Role.DOCTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + "😀".repeat(5000) + "\"}" )).andExpect(status().isCreated());
        assertThat(messages.findThread(maja, doctor).get(1).getContent()).isEqualTo("😀".repeat(5000));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsInvalidUnknownSelfAndProfessionalTargets(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        for (String id : List.of("0", "-1", "invalid", "1.5")) rejected(professional, role, id, "{\"content\":\"text\"}", 400);
        rejected(professional, role, "9223372036854775807", "{\"content\":\"text\"}", 404);
        for (Long id : List.of(doctor, staff)) rejected(professional, role, id.toString(), "{\"content\":\"text\"}", 403);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void staleOrDeletedProfessionalIsForbidden(Role role) throws Exception {
        rejected(ebba, role, maja.toString(), "{\"content\":\"text\"}", 403);
        rejected(-1L, role, maja.toString(), "{\"content\":\"text\"}", 403);
    }

    @Test
    void anonymousPatientsAndMissingOrInvalidCsrfCannotReply() throws Exception {
        long before = messages.count();
        mvc.perform(post(path(maja)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"text\"}"))
                .andExpect(status().isUnauthorized());
        rejected(ebba, Role.PATIENT, maja.toString(), "{\"content\":\"text\"}", 403);
        mvc.perform(post(path(maja)).with(as(doctor, Role.DOCTOR)).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"text\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path(maja)).with(as(doctor, Role.DOCTOR)).with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"text\"}"))
                .andExpect(status().isForbidden());
        assertThat(messages.count()).isEqualTo(before);
    }

    private String path(Long patientId) { return "/api/messages/inbox/" + patientId + "/reply"; }

    private void rejected(Long sender, Role role, String patient, String body, int status) throws Exception {
        long before = messages.count();
        mvc.perform(post("/api/messages/inbox/" + patient + "/reply").with(as(sender, role)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(content().string(""));
        assertThat(messages.count()).isEqualTo(before);
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
