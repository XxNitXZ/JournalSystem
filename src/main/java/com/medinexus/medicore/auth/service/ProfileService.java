package com.medinexus.medicore.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.dto.AccountProfileDto;
import com.medinexus.medicore.auth.dto.PasswordChangeRequest;
import com.medinexus.medicore.auth.dto.ProfileUpdateRequest;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;

/**
 * The signed-in user's own account. The account is always the one in the security context (by its stable ID), never
 * one named in a request. First name, last name and personnummer are read-only after registration; the holder can
 * change the login e-mail (with the current password), the optional phone number and the password. Nothing here
 * writes to FHIR. Logs contain the account ID only, never e-mail, phone numbers or passwords.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public ProfileService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /** The result of a contact change: the account and the principal to keep in the session. */
    public record ContactChange(AccountProfileDto profile, UserDto principal) {
    }

    /** @throws AccessDeniedException without a principal or if the account no longer exists */
    @Transactional(readOnly = true)
    public AccountProfileDto profile(UserDto principal) {
        return toProfile(account(principal));
    }

    /**
     * Changes the e-mail and/or phone number of the signed-in account. Everything is validated before anything is
     * written, both changes are made in one transaction, and a new e-mail requires the current password and must not
     * belong to another account. An unchanged e-mail needs no password.
     *
     * @throws AccountChangeException with a code (see {@link AccountChangeException})
     * @throws AccessDeniedException  without a principal or if the account no longer exists
     */
    @Transactional
    public ContactChange updateContact(UserDto principal, ProfileUpdateRequest update) {
        User account = account(principal);
        if (update == null || !update.emailPresent() && !update.phoneNumberPresent()) {
            throw new AccountChangeException("NOTHING_TO_CHANGE");
        }
        String email = account.getEmail();
        boolean emailChanges = false;
        if (update.emailPresent()) {
            try {
                email = AccountCredentials.email(update.email());
            } catch (IllegalArgumentException invalid) {
                throw new AccountChangeException("EMAIL_INVALID");
            }
            emailChanges = !email.equals(account.getEmail());
        }
        String phoneNumber = account.getPhoneNumber();
        if (update.phoneNumberPresent()) {
            try {
                phoneNumber = PhoneNumbers.normalize(update.phoneNumber());
            } catch (IllegalArgumentException invalid) {
                throw new AccountChangeException("PHONE_INVALID");
            }
        }
        if (emailChanges) {
            requireCurrentPassword(account, update.currentPassword());
            if (repository.existsByEmail(email)) throw new AccountChangeException("EMAIL_TAKEN");
            try {
                if (repository.updateEmail(account.getId(), email) != 1) throw new AccessDeniedException("Access denied");
            } catch (DataIntegrityViolationException concurrentDuplicate) {
                throw new AccountChangeException("EMAIL_TAKEN");
            }
            log.info("Account e-mail changed: accountId={}", account.getId());
        }
        if (update.phoneNumberPresent()) {
            if (repository.updatePhoneNumber(account.getId(), phoneNumber) != 1) {
                throw new AccessDeniedException("Access denied");
            }
            log.info("Account phone number {}: accountId={}", phoneNumber == null ? "removed" : "changed",
                    account.getId());
        }
        AccountProfileDto profile = new AccountProfileDto(account.getGivenName(), account.getFamilyName(),
                account.getPersonnummer(), email, phoneNumber, account.getRole());
        UserDto newPrincipal = new UserDto(account.getId(), email, account.getRole(), account.getGivenName(),
                account.getFamilyName());
        return new ContactChange(profile, newPrincipal);
    }

    /**
     * Changes the password of the signed-in account: the new password must follow the registration rules and match
     * its confirmation, and the current password must be correct. Only the new BCrypt hash is stored.
     *
     * @throws AccountChangeException PASSWORD_INVALID, PASSWORD_MISMATCH or CURRENT_PASSWORD_INVALID
     */
    @Transactional
    public void changePassword(UserDto principal, PasswordChangeRequest change) {
        User account = account(principal);
        if (change == null) throw new AccountChangeException("INVALID_REQUEST");
        if (!AccountCredentials.isAcceptablePassword(change.newPassword())) {
            throw new AccountChangeException("PASSWORD_INVALID");
        }
        if (!change.newPassword().equals(change.confirmPassword())) {
            throw new AccountChangeException("PASSWORD_MISMATCH");
        }
        requireCurrentPassword(account, change.currentPassword());
        if (repository.updatePasswordHash(account.getId(), passwordEncoder.encode(change.newPassword())) != 1) {
            throw new AccessDeniedException("Access denied");
        }
        log.info("Account password changed: accountId={}", account.getId());
    }

    private void requireCurrentPassword(User account, String currentPassword) {
        if (currentPassword == null || currentPassword.isEmpty()
                || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            log.info("Account change rejected, wrong current password: accountId={}", account.getId());
            throw new AccountChangeException("CURRENT_PASSWORD_INVALID");
        }
    }

    private User account(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) {
            throw new AccessDeniedException("Access denied");
        }
        return repository.findById(principal.id()).orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    private static AccountProfileDto toProfile(User account) {
        return new AccountProfileDto(account.getGivenName(), account.getFamilyName(), account.getPersonnummer(),
                account.getEmail(), account.getPhoneNumber(), account.getRole());
    }
}
