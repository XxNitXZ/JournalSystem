package com.medinexus.medicore.messaging.controller;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.apiError;
import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static com.medinexus.medicore.identityapi.FakeIdentity.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.identityapi.FakeIdentity;
import com.medinexus.medicore.identityapi.IdentityClient;
import com.medinexus.medicore.messaging.repository.MessageRepository;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MESSAGING_DB_PASSWORD", matches = ".+")
class MessageReplyIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MessageRepository messages;
    @MockitoBean private IdentityClient identityClient;
    private FakeIdentity identity;
    private MockMvc mvc;
    private Long maja;
    private Long ebba;
    private Long doctor;
    private Long staff;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        identity = FakeIdentity.on(identityClient);
        maja = identity.account(Role.PATIENT, "Maja");
        ebba = identity.account(Role.PATIENT, "Ebba");
        doctor = identity.account(Role.DOCTOR, "Doctor");
        staff = identity.account(Role.STAFF, null);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void replyPersistsAuthenticatedIdentityAndAppearsInBothThreads(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        message(maja, professional, "Question", "2026-01-01T00:00:00Z");
        Instant before = Instant.now().minusMillis(1);
        mvc.perform(post(path(maja)).with(as(professional, role))
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
        Long otherProfessional = role == Role.DOCTOR ? staff : doctor;
        Role otherRole = role == Role.DOCTOR ? Role.STAFF : Role.DOCTOR;
        mvc.perform(get("/api/messages/inbox/" + maja).with(as(otherProfessional, otherRole)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/messages/conversations/" + professional).with(as(ebba, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(messages.findCounterparts(professional).get(0).getMessageCount()).isEqualTo(2);
        assertThat(messages.findCounterparts(maja).get(0).getMessageCount()).isEqualTo(2);
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
        mvc.perform(post(path(maja)).with(as(doctor, Role.DOCTOR)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + "😀".repeat(5000) + "\"}" )).andExpect(status().isCreated());
        assertThat(messages.findThread(maja, doctor).get(1).getContent()).isEqualTo("😀".repeat(5000));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsInvalidUnknownSelfAndProfessionalTargets(Role role) throws Exception {
        Long professional = role == Role.DOCTOR ? doctor : staff;
        for (String id : List.of("0", "-1", "invalid", "1.5")) rejected(professional, role, id, "{\"content\":\"text\"}", 400);
        rejected(professional, role, FakeIdentity.unknownId().toString(), "{\"content\":\"text\"}", 404);
        for (Long id : List.of(doctor, staff)) rejected(professional, role, id.toString(), "{\"content\":\"text\"}", 403);
    }

    @Test
    void anonymousAndPatientsCannotReply() throws Exception {
        long before = messages.count();
        mvc.perform(post(path(maja)).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"text\"}"))
                .andExpect(status().isUnauthorized());
        rejected(ebba, Role.PATIENT, maja.toString(), "{\"content\":\"text\"}", 403);
        assertThat(messages.count()).isEqualTo(before);
    }

    @Test
    void identityDownMeans503AndNothingIsSaved() throws Exception {
        message(maja, doctor, "Question", "2026-01-01T00:00:00Z");
        org.mockito.Mockito.doThrow(new org.springframework.web.client.ResourceAccessException("Identity is down"))
                .when(identityClient).user(org.mockito.ArgumentMatchers.anyLong());
        rejected(doctor, Role.DOCTOR, maja.toString(), "{\"content\":\"text\"}", 503);
        long before = messages.count();
        mvc.perform(post("/api/messages").with(as(maja, Role.PATIENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":" + doctor + ",\"content\":\"text\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(messages.count()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void forgedQueryIdentityCannotAuthorizeReplyToAnotherProfessionalsConversation(Role role) throws Exception {
        Long caller = role == Role.DOCTOR ? doctor : staff;
        Long owner = role == Role.DOCTOR ? staff : doctor;
        message(maja, owner, "Private incoming message", "2026-01-01T00:00:00Z");
        long before = messages.count();
        mvc.perform(post(path(maja)).with(as(caller, role))
                        .param("senderId", owner.toString()).param("professionalId", owner.toString())
                        .param("patientId", ebba.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Unauthorized reply\"}"))
                .andExpect(status().isForbidden()).andExpect(genericError());
        assertThat(messages.count()).isEqualTo(before);
        assertThat(messages.findThread(maja, caller)).isEmpty();
    }

    @Test
    void rejectedPatientSendsDoNotPersistAgainstRealDatabase() throws Exception {
        long before = messages.count();
        for (Long recipient : List.of(maja, ebba)) {
            mvc.perform(post("/api/messages").with(as(maja, Role.PATIENT))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":" + recipient + ",\"content\":\"Forbidden patient target\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/messages").with(as(maja, Role.PATIENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":" + FakeIdentity.unknownId() + ",\"content\":\"Unknown target\"}"))
                .andExpect(status().isNotFound());
        assertThat(messages.count()).isEqualTo(before);
    }

    private String path(Long patientId) { return "/api/messages/inbox/" + patientId + "/reply"; }

    private void rejected(Long sender, Role role, String patient, String body, int status) throws Exception {
        long before = messages.count();
        mvc.perform(post("/api/messages/inbox/" + patient + "/reply").with(as(sender, role))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(genericError());
        assertThat(messages.count()).isEqualTo(before);
    }


    private Long message(Long sender, Long recipient, String text, String time) {
        return jdbc.queryForObject("INSERT INTO messages (sender_id, recipient_id, content, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, sender, recipient, text, java.sql.Timestamp.from(Instant.parse(time)));
    }

}
