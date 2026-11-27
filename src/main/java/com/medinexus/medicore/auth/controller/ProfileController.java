package com.medinexus.medicore.auth.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.common.exception.ApiError;
import com.medinexus.medicore.auth.dto.AccountProfileDto;
import com.medinexus.medicore.auth.dto.PasswordChangeRequest;
import com.medinexus.medicore.auth.dto.ProfileUpdateRequest;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.service.AccountChangeException;
import com.medinexus.medicore.auth.service.AccountTokenService;
import com.medinexus.medicore.auth.service.ProfileService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The signed-in user's own account; requires an authenticated session, and CSRF for changes ({@code SecurityConfig}).
 * <ul>
 *   <li>{@code GET /api/auth/profile}: names, personnummer, e-mail, phone number and role (names and personnummer
 *       are read-only);</li>
 *   <li>{@code PATCH /api/auth/profile}: {@code email} (with {@code currentPassword}) and/or {@code phoneNumber};
 *       any other property is 400 {@code FIELD_NOT_EDITABLE};</li>
 *   <li>{@code POST /api/auth/password}: {@code currentPassword}, {@code newPassword}, {@code confirmPassword}; 204.</li>
 * </ul>
 * After an e-mail change the principal in the session is replaced, so {@code GET /api/auth/me} returns the new
 * address at once and the session keeps referring to the same account (by ID); the session ID, authorities and CSRF
 * token are unchanged, because this is not a new sign-in. Errors: 400/403/409 with a {@code code}, never echoing
 * input.
 */
@RestController
@RequestMapping("/api/auth")
public class ProfileController {

    private final ProfileService profileService;
    private final AccountTokenService accountTokens;

    public ProfileController(ProfileService profileService, AccountTokenService accountTokens) {
        this.profileService = profileService;
        this.accountTokens = accountTokens;
    }

    @GetMapping("/profile")
    public AccountProfileDto profile(@AuthenticationPrincipal UserDto principal) {
        return profileService.profile(principal);
    }

    @PatchMapping("/profile")
    public AccountProfileDto updateProfile(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body, HttpServletResponse response) {
        ProfileService.ContactChange change = profileService.updateContact(principal, ProfileUpdateRequest.from(body));
        // The e-mail may have changed, and it is a token claim: issue a new token with the stored values.
        accountTokens.issue(change.principal().id(), response);
        return change.profile();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body) {
        profileService.changePassword(principal, PasswordChangeRequest.from(body));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AccountChangeException.class)
    public ResponseEntity<ApiError> rejected(AccountChangeException rejected) {
        HttpStatus status = switch (rejected.code()) {
            case "CURRENT_PASSWORD_INVALID" -> HttpStatus.FORBIDDEN;
            case "EMAIL_TAKEN" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ApiError.response(status, rejected.code());
    }

    /** Unreadable JSON; the body is empty so that no input is echoed. */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalidProfile() {
        return ApiError.response(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied() {
        return ApiError.response(HttpStatus.FORBIDDEN);
    }
}
