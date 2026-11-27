package com.medinexus.medicore.auth.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.patient.validation.Personnummer;

@Service
public class RegistrationService {

    private static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an account with an explicit role, the holder's first and last name and personnummer. Callers are
     * responsible for authorization. Names are stripped of surrounding whitespace and must be nonblank, at most
     * 100 characters and free of control characters. The personnummer must be valid (12 digits, a real date, not in the
     * future), is stored normalized to 12 digits and must not belong to another account. Error messages never
     * contain the submitted values.
     *
     * @throws PersonnummerAlreadyRegisteredException if another account has the personnummer
     * @throws DuplicateKeyException                  if the e-mail address is already registered
     */
    @Transactional
    public UserDto register(String email, String password, Role role, String givenName, String familyName,
            String personnummer) {
        return register(email, password, role, givenName, familyName, personnummer, null);
    }

    /**
     * As {@link #register(String, String, Role, String, String, String)}, with an optional phone number (null or
     * blank for none), validated and normalized by {@link PhoneNumbers}.
     */
    @Transactional
    public UserDto register(String email, String password, Role role, String givenName, String familyName,
            String personnummer, String phoneNumber) {
        String normalizedEmail = AccountCredentials.email(email);
        // BCrypt supports at most 72 UTF-8 bytes. Do not trim or truncate passwords.
        if (!AccountCredentials.isAcceptablePassword(password)) {
            throw new IllegalArgumentException("Password must be nonblank and at most 72 UTF-8 bytes");
        }
        if (role == null) {
            throw new IllegalArgumentException("A role is required");
        }
        String validGivenName = AccountNames.givenName(givenName);
        String validFamilyName = AccountNames.familyName(familyName);
        String validPersonnummer = Personnummer.normalize(personnummer, LocalDate.now(SWEDEN));
        if (validPersonnummer == null) {
            throw new IllegalArgumentException("A valid Swedish personnummer is required");
        }
        String validPhoneNumber = PhoneNumbers.normalize(phoneNumber);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateKeyException("Email is already registered");
        }
        if (userRepository.existsByPersonnummer(validPersonnummer)) {
            throw new PersonnummerAlreadyRegisteredException();
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setGivenName(validGivenName);
        user.setFamilyName(validFamilyName);
        user.setPersonnummer(validPersonnummer);
        user.setPhoneNumber(validPhoneNumber);
        // Flush here so the database unique constraints also reject concurrent duplicates.
        try {
            User saved = userRepository.saveAndFlush(user);
            return new UserDto(saved.getId(), saved.getEmail(), saved.getRole(), saved.getGivenName(),
                    saved.getFamilyName());
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof DuplicateKeyException
                        || cause instanceof SQLException sqlException
                        && "23505".equals(sqlException.getSQLState())) {
                    // Only the constraint name is inspected; the message may contain the duplicate value.
                    if (String.valueOf(cause.getMessage()).contains("uq_users_personnummer")) {
                        throw new PersonnummerAlreadyRegisteredException(exception);
                    }
                    throw new DuplicateKeyException("Email is already registered", exception);
                }
            }
            throw exception;
        }
    }
}
