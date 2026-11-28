package com.medinexus.medicore.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.identityapi.IdentityClient;
import com.medinexus.medicore.identityapi.IdentityClient.DirectoryUser;
import com.medinexus.medicore.messaging.dto.SendMessageRequest;
import com.medinexus.medicore.messaging.entity.Message;
import com.medinexus.medicore.messaging.repository.MessageRepository;
import com.medinexus.medicore.messaging.repository.MessageRepository.CounterpartSummary;

class MessageServiceTest {
    private final IdentityClient identity = mock(IdentityClient.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final MessageService service = new MessageService(identity, messages);
    private final UserDto principal = new UserDto(1L, "synthetic@example.test", Role.PATIENT);

    @Test
    void rejectsAbsentOrInvalidPrincipalWithoutAnyLookup() {
        for (UserDto user : new UserDto[] {null, new UserDto(null, null, null), new UserDto(0L, null, Role.PATIENT),
                new UserDto(1L, null, null), new UserDto(1L, null, Role.DOCTOR)}) {
            assertThatThrownBy(() -> service.send(user, new SendMessageRequest(2L, "Synthetic")))
                    .isInstanceOf(AccessDeniedException.class).hasMessage("Access denied");
        }
        verifyNoInteractions(identity, messages);
    }

    @Test
    void rejectsNullRequestAndRecipientsWithoutAProfessionalRole() {
        assertThatThrownBy(() -> service.send(principal, null)).isInstanceOf(IllegalArgumentException.class);
        when(identity.user(2L)).thenReturn(Optional.of(new DirectoryUser(2L, "Synthetic", null)));
        assertThatThrownBy(() -> service.send(principal, new SendMessageRequest(2L, "Synthetic")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(messages);
    }

    @Test
    void acceptsFiveThousandCharactersAndPreservesWhitespace() {
        when(identity.user(2L)).thenReturn(Optional.of(new DirectoryUser(2L, "Synthetic", Role.STAFF)));
        String content = " " + "x".repeat(4998) + " ";
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(call -> {
            Message message = call.getArgument(0);
            assertThat(message.getContent()).isEqualTo(content);
            assertThat(message.getSenderId()).isEqualTo(1L);
            return message;
        });
        service.send(principal, new SendMessageRequest(2L, content));
    }

    @Test
    void conversationsAskIdentityOnceAndKeepTheDatabaseOrder() {
        when(messages.findCounterparts(1L)).thenReturn(List.of(summary(7L, 3, 1, "2026-01-03T00:00:00Z"),
                summary(8L, 1, 0, "2026-01-02T00:00:00Z"), summary(9L, 2, 2, "2026-01-01T00:00:00Z")));
        when(identity.usersById(List.of(7L, 8L, 9L))).thenReturn(Map.of(
                7L, new DirectoryUser(7L, "Staff #7", Role.STAFF),
                9L, new DirectoryUser(9L, "Zoe Smith", Role.DOCTOR)));
        assertThat(service.conversations(principal)).extracting(response -> response.recipientId())
                .containsExactly(7L, 9L);
        verify(identity).usersById(List.of(7L, 8L, 9L));
    }

    @Test
    void inboxAsksOnlyAboutPatientsWhoWroteFirst() {
        UserDto doctor = new UserDto(1L, "synthetic@example.test", Role.DOCTOR);
        when(messages.findCounterparts(1L)).thenReturn(List.of(summary(7L, 1, 0, "2026-01-03T00:00:00Z"),
                summary(8L, 2, 1, "2026-01-02T00:00:00Z")));
        when(identity.usersById(List.of(8L))).thenReturn(Map.of(8L, new DirectoryUser(8L, "Maja", Role.PATIENT)));
        assertThat(service.inbox(doctor)).extracting(response -> response.patientId()).containsExactly(8L);
    }

    private static CounterpartSummary summary(Long id, long count, long incoming, String last) {
        return new CounterpartSummary() {
            @Override public Long getCounterpartId() { return id; }
            @Override public Long getMessageCount() { return count; }
            @Override public Long getIncomingCount() { return incoming; }
            @Override public Instant getLastMessageAt() { return Instant.parse(last); }
        };
    }
}
