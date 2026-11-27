package com.medinexus.medicore.security.jwt;

import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_EMAIL;
import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_ROLE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_TYPE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.SERVICE_ROLE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.TYPE_SERVICE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.TYPE_USER;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.medinexus.medicore.auth.entity.Role;

/**
 * Rejects a correctly signed token whose claims don't have the shape MediCore writes, so the principal converter
 * never has to guess. A rejected token gives 401, like a forged one.
 */
public class MediCoreClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult INVALID =
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected token claims", null));

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String type = token.getClaimAsString(CLAIM_TYPE);
        String subject = token.getSubject();
        String role = token.getClaimAsString(CLAIM_ROLE);
        if (subject == null || role == null || token.getExpiresAt() == null) return INVALID;

        if (TYPE_USER.equals(type)) {
            return isAccountId(subject) && isUserRole(role) && token.getClaimAsString(CLAIM_EMAIL) != null
                    ? OAuth2TokenValidatorResult.success() : INVALID;
        }
        if (TYPE_SERVICE.equals(type)) {
            return SERVICE_ROLE.equals(role) && subject.matches("[a-z][a-z0-9-]{0,31}")
                    ? OAuth2TokenValidatorResult.success() : INVALID;
        }
        return INVALID;
    }

    private static boolean isAccountId(String subject) {
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    private static boolean isUserRole(String role) {
        for (Role candidate : Role.values()) {
            if (candidate.name().equals(role)) return true;
        }
        return false;
    }
}
