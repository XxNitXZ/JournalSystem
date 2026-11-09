package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.config.PasswordEncoderConfig;

class RegistrationServiceTest {

    /** Fictional: valid date and check digit, born 1820 (no living person can have it). */
    private static final String PERSONNUMMER = "182003151236";

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();
    private final RegistrationService service = new RegistrationService(repository, encoder);

    @ParameterizedTest
    @EnumSource(Role.class)
    void registersAccountWithExplicitRole(Role role) {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 42L);
            return saved;
        });

        UserDto user = service.register("patient@example.com", "a secure password", role, "Anna", "Testsson", PERSONNUMMER);

        assertThat(user).isEqualTo(new UserDto(42L, "patient@example.com", role, "Anna", "Testsson"));
        verify(repository).existsByEmail("patient@example.com");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(user.email());
        assertThat(saved.getValue().getRole()).isEqualTo(role);
        assertThat(saved.getValue().getFhirPatientId()).isNull();
    }

    @Test
    void rejectsDuplicateEmailBeforeHashingOrSaving() {
        PasswordEncoder unusedEncoder = mock(PasswordEncoder.class);
        when(repository.existsByEmail("patient@example.com")).thenReturn(true);

        assertThatThrownBy(() -> new RegistrationService(repository, unusedEncoder)
                .register("patient@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson", PERSONNUMMER))
                .isInstanceOf(DuplicateKeyException.class);

        verify(repository).existsByEmail("patient@example.com");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(unusedEncoder);
    }

    @Test
    void storesSaltedBcryptHashesInsteadOfPlaintext() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register("first@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson", PERSONNUMMER);
        service.register("second@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson", PERSONNUMMER);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository, times(2)).saveAndFlush(saved.capture());
        User first = saved.getAllValues().get(0);
        User second = saved.getAllValues().get(1);

        assertThat(first.getPasswordHash()).startsWith("$2").isNotEqualTo("a secure password");
        assertThat(encoder.matches("a secure password", first.getPasswordHash())).isTrue();
        assertThat(encoder.matches("wrong password", first.getPasswordHash())).isFalse();
        assertThat(second.getPasswordHash()).isNotEqualTo(first.getPasswordHash());
    }

    @Test
    void rejectsInvalidEmailsBeforeAccessingRepository() {
        for (String email : new String[] {null, "", " ", "invalid", "a@", "@example.com",
                "a b@example.com", "a".repeat(244) + "@example.com"}) {
            assertThatThrownBy(() -> service.register(email, "a secure password", Role.PATIENT, "Anna", "Testsson", PERSONNUMMER))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidPasswordsBeforeAccessingRepository() {
        for (String password : new String[] {null, "", "   ", "x".repeat(73), "é".repeat(37)}) {
            assertThatThrownBy(() -> service.register("patient@example.com", password, Role.PATIENT, "Anna", "Testsson", PERSONNUMMER))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void requiresValidFirstAndLastNamesBeforeAccessingRepository() {
        String tooLong = "x".repeat(101);
        String[][] invalid = {
                {null, "Testsson"}, {"", "Testsson"}, {"   ", "Testsson"}, {tooLong, "Testsson"}, {"An\nna", "Testsson"},
                {"Anna", null}, {"Anna", ""}, {"Anna", "\t"}, {"Anna", tooLong}, {"Anna", "Tests\u0000son"},
                {"Anna", "Test\u0085sson"}, {null, null}};
        for (String[] names : invalid) {
            assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.PATIENT,
                    names[0], names[1], PERSONNUMMER))
                    .isInstanceOfSatisfying(IllegalArgumentException.class, failure -> {
                        assertThat(failure.getMessage()).isIn("A valid first name is required", "A valid last name is required");
                        assertThat(failure.getMessage()).doesNotContain("Anna", "Testsson", "xxx");
                    });
        }
        verifyNoInteractions(repository);
    }

    @Test
    void stripsNamesAndAcceptsOneHundredCharactersIncludingNonAsciiLetters() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // 100 code points, some outside the Basic Multilingual Plane (two UTF-16 units each).
        String longGiven = "Å".repeat(98) + "𝒜𝒜";
        String longFamily = "ö".repeat(100);

        UserDto user = service.register("patient@example.com", "a secure password", Role.DOCTOR,
                "  " + longGiven + " ", "\t" + longFamily, PERSONNUMMER);

        assertThat(user.givenName()).isEqualTo(longGiven);
        assertThat(user.familyName()).isEqualTo(longFamily);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getGivenName()).isEqualTo(longGiven);
        assertThat(saved.getValue().getFamilyName()).isEqualTo(longFamily);
    }

    @Test
    void acceptsCompoundNames() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserDto user = service.register("patient@example.com", "a secure password", Role.PATIENT,
                "Anna-Karin Maria", "von Testsson-Ödberg", PERSONNUMMER);
        assertThat(user.givenName()).isEqualTo("Anna-Karin Maria");
        assertThat(user.familyName()).isEqualTo("von Testsson-Ödberg");
    }

    @Test
    void rejectsMissingRole() {
        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", null, "Anna", "Testsson", PERSONNUMMER))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void preservesUnrelatedDatabaseErrors() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Other constraint");
        when(repository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.STAFF, "Anna", "Testsson", PERSONNUMMER))
                .isSameAs(failure);
    }

    @Test
    void acceptsPasswordAtBcryptByteLimit() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String password = "é".repeat(36);

        service.register("patient@example.com", password, Role.PATIENT, "Anna", "Testsson", PERSONNUMMER);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(encoder.matches(password, saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void translatesDatabaseRejectionOfConcurrentDuplicate() {
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("Constraint violation", new SQLException("Duplicate", "23505")));

        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson", PERSONNUMMER))
                .isInstanceOf(DuplicateKeyException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Personnummer (US-04.03) ---

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleRegistersWithANormalizedPersonnummer(Role role) {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.register("patient@example.com", "a secure password", role, "Anna", "Testsson", " 18200315-1236 ");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPersonnummer()).isEqualTo(PERSONNUMMER);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aFictionalPersonnummerWithAWrongLuhnCheckDigitIsAcceptedForEveryRole(Role role) {
        // 2004-12-20 is a real past date; the check digit of 200412201971 is wrong (it would be 2), which is irrelevant.
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.register("patient@example.com", "a secure password", role, "Anna", "Testsson", "20041220-1971");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPersonnummer()).isEqualTo("200412201971");
    }

    // --- Optional phone number (V9) ---

    @ParameterizedTest
    @EnumSource(Role.class)
    void anOptionalPhoneNumberIsStoredNormalizedForEveryRole(Role role) {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserDto user = service.register("patient@example.com", "a secure password", role, "Anna", "Testsson",
                PERSONNUMMER, " +46 (0)70-123 45.67 ");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo("+460701234567");
        // The account DTO used for sessions never carries the phone number.
        assertThat(user.toString()).doesNotContain("0701234567");
    }

    @Test
    void thePhoneNumberIsOptional() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        for (String none : new String[] {null, "", "   "}) {
            service.register("patient@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson",
                    PERSONNUMMER, none);
        }
        service.register("patient@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson", PERSONNUMMER);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository, org.mockito.Mockito.times(4)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(user -> assertThat(user.getPhoneNumber()).isNull());
    }

    @Test
    void invalidPhoneNumbersAreRejectedBeforeAccessingTheRepository() {
        for (String invalid : new String[] {"12345", "0701234567890123456", "070 ABC 12", "+46+701234567",
                "070/1234567", "++46701234567", "1".repeat(33), "+"}) {
            assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.PATIENT, "Anna",
                    "Testsson", PERSONNUMMER, invalid))
                    .isInstanceOfSatisfying(IllegalArgumentException.class,
                            failure -> assertThat(failure.getMessage()).doesNotContain("070", "12345"));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void theRegisteredAccountDoesNotExposeThePersonnummer() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 42L);
            return saved;
        });
        UserDto user = service.register("patient@example.com", "a secure password", Role.PATIENT, "Anna", "Testsson",
                PERSONNUMMER);
        assertThat(user.toString()).doesNotContain(PERSONNUMMER);
    }

    @Test
    void invalidMissingOrFuturePersonnummerAreRejectedBeforeAccessingRepository() {
        // Missing, impossible dates (30 February, month 13), 10 and 13 digits, other separators, letters, future.
        for (String invalid : new String[] {null, "", "   ", "182002301236", "182013151230", "1820031512",
                "1820031512360", "18200315+1236", "182003151236X", "ABCDEFGHIJKL", "209901011230"}) {
            assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.STAFF, "Anna",
                    "Testsson", invalid))
                    .isInstanceOfSatisfying(IllegalArgumentException.class, failure -> {
                        assertThat(failure.getMessage()).isEqualTo("A valid Swedish personnummer is required");
                        assertThat(failure.getMessage()).doesNotContain("1820");
                    });
        }
        verifyNoInteractions(repository);
    }

    @Test
    void aPersonnummerThatBelongsToAnotherAccountIsRejected() {
        when(repository.existsByPersonnummer(PERSONNUMMER)).thenReturn(true);
        assertThatThrownBy(() -> service.register("new@example.com", "a secure password", Role.PATIENT, "Anna",
                "Testsson", PERSONNUMMER))
                .isInstanceOfSatisfying(PersonnummerAlreadyRegisteredException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain(PERSONNUMMER));
        verify(repository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void aConcurrentDuplicatePersonnummerIsRecognizedByItsConstraintName() {
        when(repository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("Constraint",
                new SQLException("ERROR: duplicate key value violates unique constraint \"uq_users_personnummer\"",
                        "23505")));
        assertThatThrownBy(() -> service.register("new@example.com", "a secure password", Role.DOCTOR, "Anna",
                "Testsson", PERSONNUMMER)).isInstanceOf(PersonnummerAlreadyRegisteredException.class);
    }

    @Test
    void theEmailCheckStillComesFirst() {
        when(repository.existsByEmail("patient@example.com")).thenReturn(true);
        when(repository.existsByPersonnummer(PERSONNUMMER)).thenReturn(true);
        assertThatThrownBy(() -> service.register("patient@example.com", "a secure password", Role.PATIENT, "Anna",
                "Testsson", PERSONNUMMER)).isInstanceOf(DuplicateKeyException.class);
    }
}
