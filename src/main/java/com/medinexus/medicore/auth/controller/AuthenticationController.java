package com.medinexus.medicore.auth.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.common.exception.ApiError;
import com.medinexus.medicore.auth.dto.LoginRequest;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.service.AccountTokenService;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final AuthenticationManager authenticationManager;
    private final AccountTokenService accountTokens;

    public AuthenticationController(AuthenticationManager authenticationManager, AccountTokenService accountTokens) {
        this.authenticationManager = authenticationManager;
        this.accountTokens = accountTokens;
    }

    /** Checks the password and sets the signed token cookie. Wrong credentials give 401 without a cookie. */
    @PostMapping("/login")
    public ResponseEntity<UserDto> login(@RequestBody LoginRequest credentials, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(credentials.email(), credentials.password()));
        UserDto user = (UserDto) authentication.getPrincipal();
        return accountTokens.issue(user.id(), response).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    /**
     * The signed-in account, read fresh from the database, and a renewed token cookie with current claims. MediPortal
     * calls this on every page load, so a token is renewed while the user is active (sliding 30-minute expiry). An
     * account deleted since the token was issued gives 401 and a cleared cookie.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal UserDto user, HttpServletResponse response) {
        return accountTokens.issue(user.id(), response).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    /**
     * Temporary no-op for MediPortal, which still fetches a CSRF token before every POST and PATCH. The server no
     * longer checks it (the token cookie is SameSite=Strict). Removed together with the client code in NIT-338.
     */
    @Deprecated(forRemoval = true)
    @GetMapping("/csrf")
    public Map<String, String> csrf() {
        return Map.of("headerName", "X-CSRF-TOKEN", "token", "unused");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> invalidCredentials() {
        return ApiError.response(401);
    }
}
