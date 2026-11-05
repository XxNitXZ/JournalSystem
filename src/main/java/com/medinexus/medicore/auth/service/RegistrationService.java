package com.medinexus.medicore.auth.service;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;

@Service
public class RegistrationService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates an account with an explicit role. Callers are responsible for authorization. */
    @Transactional
    public User register(String email, String password, Role role) {
        if (email == null || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        // BCrypt supports at most 72 UTF-8 bytes. Do not trim or truncate passwords.
        if (password == null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be nonblank and at most 72 UTF-8 bytes");
        }
        if (role == null) {
            throw new IllegalArgumentException("A role is required");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateKeyException("Email is already registered");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        // Flush here so the database unique constraint also rejects concurrent duplicates.
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof DuplicateKeyException
                        || cause instanceof SQLException sqlException
                        && "23505".equals(sqlException.getSQLState())) {
                    throw new DuplicateKeyException("Email is already registered", exception);
                }
            }
            throw exception;
        }
    }
}
