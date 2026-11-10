package com.medinexus.medicore.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository;
import com.medinexus.medicore.messaging.service.MessageService;

/** Real controller, service and security chain; only database dependencies are mocked. */
@WebMvcTest(MessageController.class)
@Import({SecurityConfig.class, MessageService.class})
class MessageControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private UserRepository users;
    @MockitoBean private MessageRepository messages;
    @MockitoBean private AuthenticationService authenticationService;

    private static final String BODY = "{\"recipientId\":2,\"content\":\"Synthetic message\"}";

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void sendsFromAuthenticatedAccountToPersistedProfessionalRole(Role role) throws Exception {
        account(1L, Role.PATIENT);
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
        mvc.perform(post("/api/messages").with(signedIn(Role.PATIENT)).with(csrf())
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
        account(1L, Role.PATIENT);
        mvc.perform(post("/api/messages").with(signedIn(Role.PATIENT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        verifyNoInteractions(messages);
    }

    @Test
    void rejectsOversizedContent() throws Exception {
        account(1L, Role.PATIENT);
        mvc.perform(post("/api/messages").with(signedIn(Role.PATIENT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"recipientId\":2,\"content\":\"" + "x".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(messages);
    }

    @Test
    void rejectsAnonymousWithValidCsrf() throws Exception {
        mvc.perform(post("/api/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users, messages);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsProfessionalSessionRoles(Role role) throws Exception {
        mvc.perform(post("/api/messages").with(signedIn(role)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(users, messages);
    }

    @Test
    void requiresCsrf() throws Exception {
        mvc.perform(post("/api/messages").with(signedIn(Role.PATIENT))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/messages").with(signedIn(Role.PATIENT)).with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(users, messages);
    }

    @Test
    void rejectsDeletedSender() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.empty());
        rejected(BODY, 403);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void rejectsStalePatientSessionWhenPersistedRoleChanged(Role role) throws Exception {
        account(1L, role);
        rejected(BODY, 403);
    }

    @Test
    void rejectsMissingRecipient() throws Exception {
        account(1L, Role.PATIENT);
        when(users.findById(2L)).thenReturn(Optional.empty());
        rejected(BODY, 404);
    }

    @Test
    void rejectsPatientRecipient() throws Exception {
        account(1L, Role.PATIENT);
        account(2L, Role.PATIENT);
        rejected(BODY, 403);
    }

    @Test
    void rejectsSelfRecipient() throws Exception {
        account(1L, Role.PATIENT);
        rejected("{\"recipientId\":1,\"content\":\"Synthetic\"}", 403);
    }

    private void rejected(String body, int status) throws Exception {
        mvc.perform(post("/api/messages").with(signedIn(Role.PATIENT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(content().string(""));
        verifyNoInteractions(messages);
    }

    private void account(Long id, Role role) {
        User user = new User();
        user.setRole(role);
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    private RequestPostProcessor signedIn(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserDto(1L, "synthetic@example.test", role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
