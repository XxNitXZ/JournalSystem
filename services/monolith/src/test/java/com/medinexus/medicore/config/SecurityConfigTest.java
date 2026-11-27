package com.medinexus.medicore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AuthenticationService;

class SecurityConfigTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();
    private final AuthenticationManager manager = new SecurityConfig()
            .authenticationManager(new AuthenticationService(repository, encoder));

    @ParameterizedTest
    @CsvSource({"PATIENT, ROLE_PATIENT", "DOCTOR, ROLE_DOCTOR", "STAFF, ROLE_STAFF"})
    void assignsExactlyThePersistedRoleAuthority(Role role, String expectedAuthority) {
        persistedAccount(role);

        Authentication result = manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("USER@EXAMPLE.COM", "a secure password"));

        assertAuthenticatedAccount(result, role, expectedAuthority);
    }

    @ParameterizedTest
    @CsvSource({"PATIENT, ROLE_PATIENT", "DOCTOR, ROLE_DOCTOR", "STAFF, ROLE_STAFF"})
    void discardsClientSuppliedAuthoritiesAndRoleDetails(Role role, String expectedAuthority) {
        persistedAccount(role);
        // Even an input token claiming every role must not supply the resulting authorities.
        UsernamePasswordAuthenticationToken request = UsernamePasswordAuthenticationToken.authenticated(
                "user@example.com", "a secure password", List.of(
                        new SimpleGrantedAuthority("ROLE_PATIENT"),
                        new SimpleGrantedAuthority("ROLE_DOCTOR"),
                        new SimpleGrantedAuthority("ROLE_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        request.setDetails(new UserDto(999L, "other@example.com",
                role == Role.PATIENT ? Role.DOCTOR : Role.PATIENT));

        Authentication result = manager.authenticate(request);

        assertAuthenticatedAccount(result, role, expectedAuthority);
    }

    private void persistedAccount(Role role) {
        User account = new User();
        ReflectionTestUtils.setField(account, "id", 42L);
        account.setEmail("user@example.com");
        account.setRole(role);
        account.setPasswordHash(encoder.encode("a secure password"));
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(account));
    }

    private void assertAuthenticatedAccount(Authentication result, Role role, String expectedAuthority) {
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(expectedAuthority);
        assertThat(result.getPrincipal()).isEqualTo(new UserDto(42L, "user@example.com", role));
        assertThat(result.getCredentials()).isNull();
        verify(repository).findByEmail("user@example.com");
        verifyNoMoreInteractions(repository);
    }
}
