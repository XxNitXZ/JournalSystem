package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.config.PasswordEncoderConfig;

class RegistrationServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();
    private final RegistrationService service = new RegistrationService(repository, encoder);

    @ParameterizedTest
    @EnumSource(Role.class)
    void registersAccountWithExplicitRole(Role role) {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = service.register("patient@example.com", "a secure password", role);

        assertThat(user.getEmail()).isEqualTo("patient@example.com");
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.getFhirPatientId()).isNull();
        verify(repository).existsByEmail("patient@example.com");
        verify(repository).saveAndFlush(user);
    }

    @Test
    void rejectsDuplicateEmailBeforeHashingOrSaving() {
        PasswordEncoder unusedEncoder = mock(PasswordEncoder.class);
        when(repository.existsByEmail("patient@example.com")).thenReturn(true);

        assertThatThrownBy(() -> new RegistrationService(repository, unusedEncoder)
                .register("patient@example.com", "a secure password", Role.PATIENT))
                .isInstanceOf(DuplicateKeyException.class);

        verify(repository).existsByEmail("patient@example.com");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(unusedEncoder);
    }

    @Test
    void storesSaltedBcryptHashesInsteadOfPlaintext() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User first = service.register("first@example.com", "a secure password", Role.PATIENT);
        User second = service.register("second@example.com", "a secure password", Role.PATIENT);

        assertThat(first.getPasswordHash()).startsWith("$2").isNotEqualTo("a secure password");
        assertThat(encoder.matches("a secure password", first.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong password", first.getPasswordHash())).isFalse();
        assertThat(second.getPasswordHash()).isNotEqualTo(first.getPasswordHash());
    }

    @Test
    void rejectsInvalidEmailsBeforeAccessingRepository() {
        for (String email : new String[] {null, "", " ", "invalid", "a@", "@example.com",
                "a b@example.com", "a".repeat(244) + "@example.com"}) {
            assertThatThrownBy(() -> service.register(email, "a secure password", Role.PATIENT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidPasswordsBeforeAccessingRepository() {
        for (String password : new String[] {null, "", "   ", "x".repeat(73), "é".repeat(37)}) {
            assertThatThrownBy(() -> service.register("patient@example.com", password, Role.PATIENT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingRole() {
        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void preservesUnrelatedDatabaseErrors() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Other constraint");
        when(repository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.STAFF))
                .isSameAs(failure);
    }

    @Test
    void acceptsPasswordAtBcryptByteLimit() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String password = "é".repeat(36);

        User user = service.register("patient@example.com", password, Role.PATIENT);

        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
    }

    @Test
    void translatesDatabaseRejectionOfConcurrentDuplicate() {
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("Constraint violation", new SQLException("Duplicate", "23505")));

        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.PATIENT))
                .isInstanceOf(DuplicateKeyException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }
}
