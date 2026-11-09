package com.medinexus.medicore.auth.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.auth.dto.AccountProfileDto;
import com.medinexus.medicore.auth.dto.PasswordChangeRequest;
import com.medinexus.medicore.auth.dto.ProfileUpdateRequest;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.service.AccountChangeException;
import com.medinexus.medicore.auth.service.ProfileService;

import jakarta.servlet.http.HttpServletRequest;
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
    private final SecurityContextRepository contextRepository;

    public ProfileController(ProfileService profileService, SecurityContextRepository contextRepository) {
        this.profileService = profileService;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/profile")
    public AccountProfileDto profile(@AuthenticationPrincipal UserDto principal) {
        return profileService.profile(principal);
    }

    @PatchMapping("/profile")
    public AccountProfileDto updateProfile(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        ProfileService.ContactChange change = profileService.updateContact(principal, ProfileUpdateRequest.from(body));

        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        UsernamePasswordAuthenticationToken replacement =
                UsernamePasswordAuthenticationToken.authenticated(change.principal(), null, current.getAuthorities());
        replacement.setDetails(current.getDetails());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(replacement);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return change.profile();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserDto principal,
            @RequestBody Map<String, Object> body) {
        profileService.changePassword(principal, PasswordChangeRequest.from(body));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AccountChangeException.class)
    public ResponseEntity<Map<String, String>> rejected(AccountChangeException rejected) {
        HttpStatus status = switch (rejected.code()) {
            case "CURRENT_PASSWORD_INVALID" -> HttpStatus.FORBIDDEN;
            case "EMAIL_TAKEN" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", rejected.code()));
    }

    /** Unreadable JSON; the body is empty so that no input is echoed. */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidProfile() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
