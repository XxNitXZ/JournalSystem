package com.medinexus.medicore.auth.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.common.exception.ApiError;
import com.medinexus.medicore.auth.dto.RegistrationRequest;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.PersonnummerAlreadyRegisteredException;
import com.medinexus.medicore.auth.service.RegistrationService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Public account registration.
 *
 * <p>After a successful registration of a <strong>PATIENT</strong> account by an anonymous caller, the new user
 * is signed in with the same mechanism as {@code POST /api/auth/login}: the {@link AuthenticationManager} checks
 * the submitted credentials, the session strategy changes the session ID and rotates the CSRF token, and the
 * security context is stored in the HTTP session. The response body is unchanged; clients read the session
 * with {@code GET /api/auth/me}.
 *
 * <p>DOCTOR and STAFF accounts are created but never signed in automatically, because public registration does
 * not verify clinical roles. A failed registration never signs anybody in, and an existing session is never
 * replaced. If the automatic sign-in itself fails, the account still exists and the user can sign in manually.
 */
@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

    private final RegistrationService registrationService;
    private final AuthenticationManager authenticationManager;
    private final AccountTokenService accountTokens;

    public RegistrationController(RegistrationService registrationService, AuthenticationManager authenticationManager,
            AccountTokenService accountTokens) {
        this.registrationService = registrationService;
        this.authenticationManager = authenticationManager;
        this.accountTokens = accountTokens;
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@RequestBody RegistrationRequest request, HttpServletResponse httpResponse) {
        // Any failure here (invalid data, duplicate e-mail) propagates before a sign-in is attempted.
        UserDto user = registrationService.register(request.email(), request.password(), request.role(),
                request.givenName(), request.familyName(), request.personnummer(), request.phoneNumber());
        if (user.role() == Role.PATIENT && isAnonymous()) {
            signIn(user, request.password(), httpResponse);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    private static boolean isAnonymous() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current == null || current instanceof AnonymousAuthenticationToken || !current.isAuthenticated();
    }

    /**
     * The same steps as AuthenticationController.login: check the password, then set the token cookie. A failure
     * leaves the caller signed out (no cookie is set).
     */
    private void signIn(UserDto user, String password, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(user.email(), password));
            if (!(authentication.getPrincipal() instanceof UserDto principal) || !user.id().equals(principal.id())
                    || principal.role() != Role.PATIENT) {
                throw new IllegalStateException("Authenticated principal does not match the registered account");
            }
            accountTokens.issue(principal.id(), response);
        } catch (RuntimeException failure) {
            log.warn("Automatic sign-in after registration failed: accountId={}, cause={}", user.id(),
                    failure.getClass().getSimpleName());
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidRegistration() {
        return ApiError.response(HttpStatus.BAD_REQUEST);
    }

    /** The personnummer belongs to another account; a code tells the client which field conflicts. */
    @ExceptionHandler(PersonnummerAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> duplicatePersonnummer() {
        return ApiError.response(HttpStatus.CONFLICT, "PERSONNUMMER_TAKEN");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicateEmail() {
        return ApiError.response(HttpStatus.CONFLICT);
    }
}
