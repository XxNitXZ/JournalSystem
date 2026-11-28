package com.medinexus.medicore.messaging.controller;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.apiError;
import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static com.medinexus.medicore.identityapi.FakeIdentity.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PatientConversationsIntegrationTest {
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
        assertThat(messages.findCounterparts(ebba)).allSatisfy(summary -> assertThat(summary.getMessageCount()).isEqualTo(1));
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
    void sameProfessionalUrlUsesSessionIdentityDespiteForgedPatientQueryParameters() throws Exception {
        Long majaSent = message(maja, doctor, "MAJA ONLY", "2026-01-01T00:00:00Z");
        Long majaReply = message(doctor, maja, "MAJA REPLY", "2026-01-02T00:00:00Z");
        Long ebbaSent = message(ebba, doctor, "EBBA ONLY", "2026-01-01T00:00:00Z");
        Long ebbaReply = message(doctor, ebba, "EBBA REPLY", "2026-01-02T00:00:00Z");
        for (Long patient : List.of(maja, ebba)) {
            Long other = patient.equals(maja) ? ebba : maja;
            Long sentId = patient.equals(maja) ? majaSent : ebbaSent;
            Long replyId = patient.equals(maja) ? majaReply : ebbaReply;
            String otherName = patient.equals(maja) ? "EBBA" : "MAJA";
            mvc.perform(get("/api/messages/conversations/" + doctor)
                            .param("patientId", other.toString()).param("senderId", other.toString())
                            .param("userId", other.toString()).with(as(patient, Role.PATIENT)))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(2))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(sentId))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[1].id").value(replyId))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(otherName))));
        }
    }

    @Test
    void replyOnlyConversationRemainsPrivateAndCannotBeSelectedThroughAnotherPatientsId() throws Exception {
        message(doctor, ebba, "EBBA PRIVATE REPLY", "2026-01-01T00:00:00Z");
        mvc.perform(get("/api/messages/conversations").param("patientId", ebba.toString()).with(as(maja, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/messages/conversations").with(as(ebba, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"recipientId":%d,"displayName":"Doctor Synthetic","role":"DOCTOR","messageCount":1,"lastMessageAt":"2026-01-01T00:00:00Z"}]
                        """.formatted(doctor), org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(get("/api/messages/conversations/" + doctor).with(as(maja, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/messages/conversations/" + ebba).with(as(maja, Role.PATIENT)))
                .andExpect(status().isForbidden()).andExpect(genericError());
    }

    @Test
    void listOrdersMostRecentFirst() {
        message(maja, doctor, "Older", "2026-01-01T00:00:00Z");
        message(staff, maja, "Newer", "2026-01-02T00:00:00Z");
        assertThat(messages.findCounterparts(maja)).extracting(summary -> summary.getCounterpartId())
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
        mvc.perform(get("/api/messages/conversations/" + FakeIdentity.unknownId()).with(as(maja, Role.PATIENT)))
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
    void professionalSessionsAreForbidden(Role role) throws Exception {
        for (String path : paths()) {
            mvc.perform(get(path).with(as(doctor, role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void conversationsShowOnlyProfessionalsIdentityKnows() throws Exception {
        // Replaces the Lab 1 SQL filter "u.role IN (DOCTOR, STAFF)" (see ProfessionalInboxIntegrationTest).
        message(maja, ebba, "TO ANOTHER PATIENT", "2026-01-03T00:00:00Z");
        message(FakeIdentity.unknownId(), maja, "FROM AN UNKNOWN ACCOUNT", "2026-01-03T00:00:00Z");
        message(maja, doctor, "Question", "2026-01-01T00:00:00Z");
        mvc.perform(get("/api/messages/conversations").with(as(maja, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"recipientId":%d,"displayName":"Doctor Synthetic","role":"DOCTOR","messageCount":1,"lastMessageAt":"2026-01-01T00:00:00Z"}]
                        """.formatted(doctor), org.springframework.test.json.JsonCompareMode.STRICT));
    }

    private List<String> paths() {
        return List.of("/api/messages/conversations", "/api/messages/conversations/" + doctor);
    }


    private Long message(Long sender, Long recipient, String text, String time) {
        return jdbc.queryForObject("INSERT INTO messages (sender_id, recipient_id, content, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, sender, recipient, text, java.sql.Timestamp.from(Instant.parse(time)));
    }

}
