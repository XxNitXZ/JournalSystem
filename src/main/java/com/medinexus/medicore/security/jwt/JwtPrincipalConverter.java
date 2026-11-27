package com.medinexus.medicore.security.jwt;

import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_EMAIL;
import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_FAMILY_NAME;
import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_GIVEN_NAME;
import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_ROLE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.CLAIM_TYPE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.SERVICE_ROLE;
import static com.medinexus.medicore.security.jwt.JwtTokenService.TYPE_SERVICE;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;

/**
 * Turns a verified token into the same principal the controllers already use. A user token becomes a
 * {@link UserDto} with authority {@code ROLE_<role>}, so every {@code @AuthenticationPrincipal UserDto} keeps
 * working unchanged. A service token becomes a {@link ServicePrincipal} with {@code ROLE_SERVICE}.
 *
 * <p>The verified {@link Jwt} is kept as the credentials, so later code can read the FHIR id claims
 * ({@code fhirPatientId}, {@code fhirPractitionerId}) without a database lookup. Claims were already checked by
 * {@link MediCoreClaimsValidator}.
 */
public class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        if (TYPE_SERVICE.equals(token.getClaimAsString(CLAIM_TYPE))) {
            return UsernamePasswordAuthenticationToken.authenticated(new ServicePrincipal(token.getSubject()), token,
                    List.of(new SimpleGrantedAuthority("ROLE_" + SERVICE_ROLE)));
        }
        Role role = Role.valueOf(token.getClaimAsString(CLAIM_ROLE));
        UserDto user = new UserDto(Long.valueOf(token.getSubject()), token.getClaimAsString(CLAIM_EMAIL), role,
                token.getClaimAsString(CLAIM_GIVEN_NAME), token.getClaimAsString(CLAIM_FAMILY_NAME));
        return UsernamePasswordAuthenticationToken.authenticated(user, token,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
}
