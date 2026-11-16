package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.config.PasswordEncoderConfig;

class AuthenticationServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder encoder = spy(new PasswordEncoderConfig().passwordEncoder());
    private final AuthenticationService service = new AuthenticationService(repository, encoder);

    @ParameterizedTest
    @EnumSource(Role.class)
    void authenticatesPersistedIdentityAndRole(Role role) {
        User account = account(role, "a secure password");
        String originalHash = account.getPasswordHash();
        when(repository.findByEmail("patient@example.com")).thenReturn(Optional.of(account));

        UserDto authenticated = service.authenticate("PATIENT@EXAMPLE.COM", "a secure password");

        assertThat(authenticated.id()).isEqualTo(42L);
        assertThat(authenticated.email()).isEqualTo("patient@example.com");
        assertThat(authenticated.role()).isEqualTo(role);
        assertThat(account.getPasswordHash()).isEqualTo(originalHash);
        verify(encoder).matches("a secure password", originalHash);
        verify(repository).findByEmail("patient@example.com");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void returnsStoredNamesAndNullForAccountsWithoutNames() {
        User named = account(Role.DOCTOR, "a secure password");
        named.setGivenName("Anna");
        named.setFamilyName("Testsson");
        when(repository.findByEmail("patient@example.com")).thenReturn(Optional.of(named));
        assertThat(service.authenticate("patient@example.com", "a secure password"))
                .isEqualTo(new UserDto(42L, "patient@example.com", Role.DOCTOR, "Anna", "Testsson"));

        User unnamed = account(Role.PATIENT, "a secure password");
        when(repository.findByEmail("patient@example.com")).thenReturn(Optional.of(unnamed));
        UserDto existing = service.authenticate("patient@example.com", "a secure password");
        assertThat(existing.givenName()).isNull();
        assertThat(existing.familyName()).isNull();
    }

    @Test
    void rejectsIncorrectPasswordWithGenericError() {
        User account = account(Role.PATIENT, "a secure password");
        when(repository.findByEmail("patient@example.com")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.authenticate("patient@example.com", "wrong password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid email or password");

        verify(encoder).matches("wrong password", account.getPasswordHash());
        verify(repository).findByEmail("patient@example.com");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsUnknownUserWithSameGenericError() {
        when(repository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("unknown@example.com", "a secure password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid email or password");
        verifyNoInteractions(encoder);
    }

    @Test
    void rejectsInvalidEmailsBeforeRepositoryLookup() {
        for (String email : new String[] {null, "", " ", "invalid", "a@", "@example.com",
                "a b@example.com", " patient@example.com", "a".repeat(244) + "@example.com"}) {
            assertThatThrownBy(() -> service.authenticate(email, "a secure password"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid email or password");
        }
        verifyNoInteractions(repository, encoder);
    }

    @Test
    void rejectsInvalidPasswordsBeforeRepositoryLookup() {
        for (String password : new String[] {null, "", "   ", "x".repeat(73), "é".repeat(37)}) {
            assertThatThrownBy(() -> service.authenticate("patient@example.com", password))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid email or password");
        }
        verifyNoInteractions(repository, encoder);
    }

    @Test
    void preservesPasswordWhitespaceAndAcceptsBcryptByteLimit() {
        String password = " " + "é".repeat(35) + " ";
        User account = account(Role.STAFF, password);
        when(repository.findByEmail("patient@example.com")).thenReturn(Optional.of(account));

        assertThat(service.authenticate("patient@example.com", password))
                .isEqualTo(new UserDto(42L, "patient@example.com", Role.STAFF));
        verify(encoder).matches(password, account.getPasswordHash());
    }

    @Test
    void normalizesEmailIndependentlyOfDefaultLocale() {
        User account = account(Role.DOCTOR, "a secure password");
        when(repository.findByEmail("patient@example.com")).thenReturn(Optional.of(account));
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(service.authenticate("PATIENT@EXAMPLE.COM", "a secure password"))
                    .isEqualTo(new UserDto(42L, "patient@example.com", Role.DOCTOR));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    private User account(Role role, String password) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 42L);
        user.setEmail("patient@example.com");
        user.setRole(role);
        user.setPasswordHash(new PasswordEncoderConfig().passwordEncoder().encode(password));
        return user;
    }
}
