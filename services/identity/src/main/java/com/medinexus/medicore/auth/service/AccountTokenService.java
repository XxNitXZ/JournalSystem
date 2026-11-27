package com.medinexus.medicore.auth.service;

import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.security.jwt.JwtTokenService;
import com.medinexus.medicore.security.jwt.UserClaims;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Issues the user token cookie from the account as it is stored now. Called on login, after registration, after a
 * profile change, and by {@code GET /api/auth/me} and {@code GET /api/journal-access/me}. Re-reading the account each
 * time keeps the claims current: a patient whose journal access was just approved gets their {@code fhirPatientId}
 * claim the next time MediPortal calls one of those endpoints, without signing in again.
 */
@Service
public class AccountTokenService {

    private final UserRepository users;
    private final JwtTokenService tokens;

    public AccountTokenService(UserRepository users, JwtTokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /**
     * Loads the account, sets a fresh token cookie on the response and returns the account. If the account no longer
     * exists, the cookie is cleared instead and the result is empty.
     */
    @Transactional(readOnly = true)
    public Optional<UserDto> issue(Long accountId, HttpServletResponse response) {
        Optional<User> account = accountId == null ? Optional.empty() : users.findById(accountId);
        if (account.isEmpty()) {
            clear(response);
            return Optional.empty();
        }
        User user = account.get();
        String token = tokens.userToken(new UserClaims(user.getId(), user.getEmail(), user.getRole(),
                user.getGivenName(), user.getFamilyName(), user.getFhirPatientId(), user.getFhirPractitionerId()));
        response.addHeader(HttpHeaders.SET_COOKIE, tokens.tokenCookie(token).toString());
        return Optional.of(new UserDto(user.getId(), user.getEmail(), user.getRole(), user.getGivenName(),
                user.getFamilyName()));
    }

    /** Overwrites the token cookie with an expired one. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, tokens.clearedCookie().toString());
    }
}
