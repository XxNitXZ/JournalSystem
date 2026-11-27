package com.medinexus.medicore.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.auth.dto.AccountProfileDto;
import com.medinexus.medicore.auth.dto.PasswordChangeRequest;
import com.medinexus.medicore.auth.dto.ProfileUpdateRequest;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;

/** The signed-in user's own account: read-only identity, e-mail, phone and password. Fictional data only. */
@ExtendWith(OutputCaptureExtension.class)
class ProfileServiceTest {

    private static final String PASSWORD = "Current-Passw0rd";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private final UserRepository repository = mock(UserRepository.class);
    private final ProfileService service = new ProfileService(repository, ENCODER);
    private final UserDto principal = new UserDto(7L, "anna@example.test", Role.PATIENT, "Anna", "Testsson");
    private User account;

    @BeforeEach
    void storedAccount() {
        account = new User();
        ReflectionTestUtils.setField(account, "id", 7L);
        account.setEmail("anna@example.test");
        account.setPasswordHash(ENCODER.encode(PASSWORD));
        account.setRole(Role.PATIENT);
        account.setGivenName("Anna");
        account.setFamilyName("Testsson");
        account.setPersonnummer("200412201971");
        account.setPhoneNumber("+46701234567");
        when(repository.findById(7L)).thenReturn(Optional.of(account));
        when(repository.updateEmail(eq(7L), anyString())).thenReturn(1);
        when(repository.updatePhoneNumber(eq(7L), any())).thenReturn(1);
        when(repository.updatePasswordHash(eq(7L), anyString())).thenReturn(1);
    }

    private static ProfileUpdateRequest update(Object... keyValues) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) body.put((String) keyValues[i], keyValues[i + 1]);
        return ProfileUpdateRequest.from(body);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AccountChangeException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private void assertNothingWritten() {
        verify(repository, never()).updateEmail(anyLong(), anyString());
        verify(repository, never()).updatePhoneNumber(anyLong(), any());
        verify(repository, never()).updatePasswordHash(anyLong(), anyString());
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theProfileShowsTheOwnAccountIncludingTheReadOnlyIdentity() {
        assertThat(service.profile(principal)).isEqualTo(new AccountProfileDto("Anna", "Testsson", "200412201971",
                "anna@example.test", "+46701234567", Role.PATIENT));
    }

    @Test
    void thePhoneNumberCanBeAddedChangedAndRemovedWithoutThePassword() {
        var changed = service.updateContact(principal, update("phoneNumber", " 070-123 45 67 "));
        verify(repository).updatePhoneNumber(7L, "0701234567");
        assertThat(changed.profile().phoneNumber()).isEqualTo("0701234567");
        assertThat(changed.principal()).isEqualTo(principal);

        assertThat(service.updateContact(principal, update("phoneNumber", "+44 (20) 7946 0958")).profile().phoneNumber())
                .isEqualTo("+442079460958");
        assertThat(service.updateContact(principal, update("phoneNumber", null)).profile().phoneNumber()).isNull();
        assertThat(service.updateContact(principal, update("phoneNumber", "  ")).profile().phoneNumber()).isNull();
        verify(repository, org.mockito.Mockito.times(2)).updatePhoneNumber(7L, null);
        verify(repository, never()).updateEmail(anyLong(), anyString());
    }

    @Test
    void theEmailChangesOnlyWithTheCurrentPasswordAndTheSessionPrincipalFollows(CapturedOutput output) {
        var changed = service.updateContact(principal, update("email", "Anna.New@Example.TEST",
                "currentPassword", PASSWORD));
        verify(repository).updateEmail(7L, "anna.new@example.test");
        assertThat(changed.profile().email()).isEqualTo("anna.new@example.test");
        // Same account ID, role and names; only the e-mail differs.
        assertThat(changed.principal()).isEqualTo(new UserDto(7L, "anna.new@example.test", Role.PATIENT, "Anna",
                "Testsson"));
        assertThat(output.getAll()).contains("Account e-mail changed: accountId=7")
                .doesNotContain("anna.new@example.test", PASSWORD);
    }

    @Test
    void aWrongOrMissingCurrentPasswordIsRejectedAndNothingChanges() {
        assertCode(() -> service.updateContact(principal, update("email", "new@example.test",
                "currentPassword", "wrong")), "CURRENT_PASSWORD_INVALID");
        assertCode(() -> service.updateContact(principal, update("email", "new@example.test")),
                "CURRENT_PASSWORD_INVALID");
        assertCode(() -> service.updateContact(principal, update("email", "new@example.test", "phoneNumber",
                "0701234567", "currentPassword", "")), "CURRENT_PASSWORD_INVALID");
        assertNothingWritten();
    }

    @Test
    void anEmailOfAnotherAccountIsRejectedAlsoWhenRegisteredConcurrently() {
        when(repository.existsByEmail("taken@example.test")).thenReturn(true);
        assertCode(() -> service.updateContact(principal, update("email", "taken@example.test",
                "currentPassword", PASSWORD)), "EMAIL_TAKEN");
        verify(repository, never()).updateEmail(anyLong(), anyString());

        when(repository.updateEmail(7L, "race@example.test")).thenThrow(new DataIntegrityViolationException("uq"));
        assertCode(() -> service.updateContact(principal, update("email", "race@example.test",
                "currentPassword", PASSWORD)), "EMAIL_TAKEN");
    }

    @Test
    void anUnchangedEmailNeedsNoPassword() {
        service.updateContact(principal, update("email", "ANNA@example.test", "phoneNumber", "0701234567"));
        verify(repository, never()).updateEmail(anyLong(), anyString());
        verify(repository).updatePhoneNumber(7L, "0701234567");
    }

    @Test
    void invalidValuesAreRejectedBeforeAnythingIsWritten() {
        for (String email : new String[] {"", "not-an-email", "a@b", "x".repeat(250) + "@example.test"}) {
            assertCode(() -> service.updateContact(principal, update("email", email, "currentPassword", PASSWORD)),
                    "EMAIL_INVALID");
        }
        assertCode(() -> service.updateContact(principal, update("email", null, "currentPassword", PASSWORD)),
                "EMAIL_INVALID");
        for (String phone : new String[] {"12345", "0701234567890123456", "070 ABC", "+46+70", "070/1234567",
                "1".repeat(40), "++46701234567"}) {
            // A valid new e-mail in the same request is not written either.
            assertCode(() -> service.updateContact(principal, update("email", "new@example.test",
                    "phoneNumber", phone, "currentPassword", PASSWORD)), "PHONE_INVALID");
        }
        assertCode(() -> service.updateContact(principal, update()), "NOTHING_TO_CHANGE");
        assertNothingWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"givenName", "familyName", "personnummer", "role", "id", "userId", "fhirPatientId",
            "fhirPractitionerId", "password", "passwordHash", "unknown"})
    void identityAndOtherFieldsCannotBeChanged(String field) {
        Map<String, Object> body = new HashMap<>();
        body.put("phoneNumber", "0701234567");
        body.put(field, "Changed");
        assertCode(() -> ProfileUpdateRequest.from(body), "FIELD_NOT_EDITABLE");
        Map<String, Object> passwordBody = new HashMap<>(Map.of("currentPassword", PASSWORD,
                "newPassword", "New-Passw0rd", "confirmPassword", "New-Passw0rd"));
        passwordBody.put(field, "Changed");
        assertCode(() -> PasswordChangeRequest.from(passwordBody), "FIELD_NOT_EDITABLE");
    }

    @Test
    void nonTextValuesAreRejected() {
        assertCode(() -> update("phoneNumber", 701234567), "INVALID_REQUEST");
        assertCode(() -> update("email", Map.of("a", "b")), "INVALID_REQUEST");
        assertCode(() -> ProfileUpdateRequest.from(null), "INVALID_REQUEST");
    }

    @Test
    void thePasswordChangesOnlyWithTheCurrentPasswordAndIsStoredAsAHash(CapturedOutput output) {
        service.changePassword(principal, new PasswordChangeRequest(PASSWORD, "New-Passw0rd ", "New-Passw0rd "));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).updatePasswordHash(eq(7L), hash.capture());
        assertThat(hash.getValue()).startsWith("$2").doesNotContain("New-Passw0rd");
        // The new password exactly as entered (not trimmed) works; the old one does not.
        assertThat(ENCODER.matches("New-Passw0rd ", hash.getValue())).isTrue();
        assertThat(ENCODER.matches(PASSWORD, hash.getValue())).isFalse();
        assertThat(output.getAll()).contains("Account password changed: accountId=7")
                .doesNotContain("New-Passw0rd", PASSWORD);
    }

    @Test
    void wrongCurrentPasswordsMismatchesAndInvalidPasswordsAreRejected() {
        assertCode(() -> service.changePassword(principal, new PasswordChangeRequest("wrong", "New-Passw0rd",
                "New-Passw0rd")), "CURRENT_PASSWORD_INVALID");
        assertCode(() -> service.changePassword(principal, new PasswordChangeRequest(null, "New-Passw0rd",
                "New-Passw0rd")), "CURRENT_PASSWORD_INVALID");
        assertCode(() -> service.changePassword(principal, new PasswordChangeRequest(PASSWORD, "New-Passw0rd",
                "Other-Passw0rd")), "PASSWORD_MISMATCH");
        for (String invalid : new String[] {null, "", "   ", "å".repeat(37)}) {
            assertCode(() -> service.changePassword(principal, new PasswordChangeRequest(PASSWORD, invalid, invalid)),
                    "PASSWORD_INVALID");
        }
        assertNothingWritten();
        assertThat(new PasswordChangeRequest(PASSWORD, "x", "x").toString()).doesNotContain(PASSWORD);
    }

    @Test
    void onlyTheSignedInAccountCanBeChanged() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        for (UserDto other : new UserDto[] {null, new UserDto(null, "x@example.test", Role.STAFF),
                new UserDto(0L, "x@example.test", Role.STAFF), new UserDto(99L, "x@example.test", Role.STAFF)}) {
            assertThatThrownBy(() -> service.profile(other)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.updateContact(other, update("phoneNumber", "0701234567")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.changePassword(other, new PasswordChangeRequest(PASSWORD, "New-Passw0rd",
                    "New-Passw0rd"))).isInstanceOf(AccessDeniedException.class);
        }
        assertNothingWritten();
    }
}
