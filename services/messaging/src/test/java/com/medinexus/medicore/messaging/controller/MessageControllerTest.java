package com.medinexus.medicore.messaging.controller;

import static com.medinexus.medicore.common.exception.ApiErrorMatchers.genericError;
import static com.medinexus.medicore.identityapi.FakeIdentity.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.identityapi.IdentityClient;
import com.medinexus.medicore.identityapi.IdentityClient.DirectoryUser;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository;
import com.medinexus.medicore.messaging.service.MessageService;

/** Real controller, service and security chain; Identity and the database are mocked. */
@WebMvcTest(MessageController.class)
@Import({SecurityConfig.class, MessageService.class})
class MessageControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private IdentityClient identity;
    @MockitoBean private MessageRepository messages;

    private static final String BODY = "{\"recipientId\":2,\"content\":\"Synthetic message\"}";

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void sendsFromTheTokensAccountToAProfessionalIdentityKnows(Role role) throws Exception {
        account(2L, role);
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(call -> {
            Message message = call.getArgument(0);
            assertThat(message.getSenderId()).isEqualTo(1L);
            assertThat(message.getRecipientId()).isEqualTo(2L);
            assertThat(message.getContent()).isEqualTo("Synthetic message");
            ReflectionTestUtils.setField(message, "id", 10L);
            ReflectionTestUtils.setField(message, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
            return message;
        });
        mvc.perform(post("/api/messages").with(as(1L, Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json("{\"id\":10,\"createdAt\":\"2026-01-01T00:00:00Z\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"recipientId\":2}",
            "{\"recipientId\":2,\"content\":null}", "{\"recipientId\":2,\"content\":\" \\t\\n\"}",
            "{\"recipientId\":0,\"content\":\"Synthetic\"}", "{\"content\":\"Synthetic\"}",
            "{\"recipientId\":2.5,\"content\":\"Synthetic\"}", "{\"recipientId\":\"2\",\"content\":\"Synthetic\"}",
            "{\"recipientId\":2,\"content\":42}", "{\"recipientId\":2,\"content\":\"Synthetic\",\"senderId\":99}"})
    void rejectsInvalidOrSenderSuppliedInput(String body) throws Exception {
        mvc.perform(post("/api/messages").with(as(1L, Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(genericError());
        verifyNoInteractions(identity, messages);
    }

    @Test
    void rejectsOversizedContent() throws Exception {
        mvc.perform(post("/api/messages").with(as(1L, Role.PATIENT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":2,\"content\":\"" + "x".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(identity, messages);
    }

    @Test
    void rejectsAnonymous() throws Exception {
        mvc.perform(post("/api/messages").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(genericError());
        verifyNoInteractions(identity, messages);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsProfessionalTokens(Role role) throws Exception {
        mvc.perform(post("/api/messages").with(as(1L, role))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(identity, messages);
    }

    @Test
    void rejectsARecipientIdentityDoesNotKnow() throws Exception {
        when(identity.user(2L)).thenReturn(Optional.empty());
        rejected(BODY, 404);
    }

    @Test
    void rejectsPatientRecipient() throws Exception {
        account(2L, Role.PATIENT);
        rejected(BODY, 403);
    }

    @Test
    void rejectsSelfRecipient() throws Exception {
        rejected("{\"recipientId\":1,\"content\":\"Synthetic\"}", 403);
        verifyNoInteractions(identity);
    }

    @Test
    void identityDownOrFailingMeansNothingIsSaved() throws Exception {
        doThrow(new ResourceAccessException("Identity is down")).when(identity).user(anyLong());
        rejected(BODY, 503);
        doThrow(HttpServerErrorException.create(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "boom",
                null, null, null)).when(identity).user(anyLong());
        rejected(BODY, 502);
    }

    @Test
    void recipientsComeFromIdentityInItsOrder() throws Exception {
        when(identity.usersWithRoles(Role.DOCTOR, Role.STAFF)).thenReturn(List.of(
                new DirectoryUser(5L, "Amy", Role.DOCTOR), new DirectoryUser(3L, "Amy", Role.STAFF),
                new DirectoryUser(2L, "Zoe Smith", Role.DOCTOR)));
        mvc.perform(get("/api/messages/recipients").with(as(1L, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"id":5,"displayName":"Amy","role":"DOCTOR"},
                         {"id":3,"displayName":"Amy","role":"STAFF"},
                         {"id":2,"displayName":"Zoe Smith","role":"DOCTOR"}]
                        """, JsonCompareMode.STRICT));
        verifyNoInteractions(messages);
    }

    @Test
    void emptyDirectoryReturnsEmptyArray() throws Exception {
        when(identity.usersWithRoles(Role.DOCTOR, Role.STAFF)).thenReturn(List.of());
        mvc.perform(get("/api/messages/recipients").with(as(1L, Role.PATIENT)))
                .andExpect(status().isOk()).andExpect(content().json("[]", JsonCompareMode.STRICT));
    }

    @Test
    void anonymousCannotDiscoverRecipients() throws Exception {
        mvc.perform(get("/api/messages/recipients")).andExpect(status().isUnauthorized());
        verifyNoInteractions(identity, messages);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void professionalTokensCannotDiscoverRecipients(Role role) throws Exception {
        mvc.perform(get("/api/messages/recipients").with(as(1L, role)))
                .andExpect(status().isForbidden()).andExpect(genericError());
        verify(identity, never()).usersWithRoles(any(Role[].class));
    }

    @Test
    void serviceTokensCannotUseTheUserRoutes() throws Exception {
        String token = com.medinexus.medicore.security.jwt.TestTokens.tokens().serviceToken("identity");
        mvc.perform(get("/api/messages/recipients").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        verifyNoInteractions(identity, messages);
    }

    private void rejected(String body, int status) throws Exception {
        mvc.perform(post("/api/messages").with(as(1L, Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(genericError());
        verifyNoInteractions(messages);
    }

    private void account(Long id, Role role) {
        when(identity.user(id)).thenReturn(Optional.of(new DirectoryUser(id, "Synthetic", role)));
    }
}
