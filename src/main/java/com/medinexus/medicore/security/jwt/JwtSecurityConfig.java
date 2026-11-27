package com.medinexus.medicore.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Beans for JWT authentication: one HS256 key from {@code medinexus.jwt.secret} signs and verifies every token.
 * Self-contained so it can move to the shared {@code common} module (NIT-788) and be imported by every service.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtSecurityConfig {

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    /** Verifies the HS256 signature, expiry (60 s clock skew), issuer and MediCore's claim shape. */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), new MediCoreClaimsValidator()));
        return decoder;
    }

    @Bean
    public JwtTokenService jwtTokenService(JwtEncoder encoder, JwtProperties properties) {
        return new JwtTokenService(encoder, properties, Clock.systemUTC());
    }

    @Bean
    public CookieBearerTokenResolver cookieBearerTokenResolver(JwtProperties properties, JwtDecoder decoder) {
        return new CookieBearerTokenResolver(properties.cookieName(), decoder);
    }

    @Bean
    public JwtPrincipalConverter jwtPrincipalConverter() {
        return new JwtPrincipalConverter();
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
