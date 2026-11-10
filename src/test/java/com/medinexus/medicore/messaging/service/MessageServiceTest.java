package com.medinexus.medicore.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.messaging.dto.SendMessageRequest;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository;

class MessageServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final MessageService service = new MessageService(users, messages);
    private final UserDto principal = new UserDto(1L, "synthetic@example.test", Role.PATIENT);

    @Test
    void rejectsAbsentOrInvalidPrincipalWithoutDatabaseAccess() {
        for (UserDto user : new UserDto[] {null, new UserDto(null, null, null), new UserDto(0L, null, null)}) {
            assertThatThrownBy(() -> service.send(user, new SendMessageRequest(2L, "Synthetic")))
                    .isInstanceOf(AccessDeniedException.class).hasMessage("Access denied");
        }
        verifyNoInteractions(users, messages);
    }

    @Test
    void rejectsNullRequestAndUnsupportedPersistedRoles() {
        account(1L, Role.PATIENT);
        assertThatThrownBy(() -> service.send(principal, null)).isInstanceOf(IllegalArgumentException.class);
        account(1L, null);
        assertThatThrownBy(() -> service.send(principal, new SendMessageRequest(2L, "Synthetic")))
                .isInstanceOf(AccessDeniedException.class);
        account(1L, Role.PATIENT);
        account(2L, null);
        assertThatThrownBy(() -> service.send(principal, new SendMessageRequest(2L, "Synthetic")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(messages);
    }

    private void account(Long id, Role role) {
        User user = new User();
        user.setRole(role);
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    @Test
    void acceptsFiveThousandCharactersPreservesWhitespaceAndIgnoresSessionRole() {
        account(1L, Role.PATIENT);
        account(2L, Role.STAFF);
        String content = " " + "x".repeat(4998) + " ";
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(call -> {
            Message message = call.getArgument(0);
            assertThat(message.getContent()).isEqualTo(content);
            assertThat(message.getSenderId()).isEqualTo(1L);
            return message;
        });
        service.send(new UserDto(1L, "untrusted@example.test", Role.DOCTOR), new SendMessageRequest(2L, content));
    }
}
