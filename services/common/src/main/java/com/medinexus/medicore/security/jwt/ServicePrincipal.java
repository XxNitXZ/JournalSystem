package com.medinexus.medicore.security.jwt;

/**
 * The caller of an {@code /internal/**} endpoint when it authenticated with a service token: another MediCore
 * service, never a person.
 *
 * @param name the calling service, e.g. {@code clinical}
 */
public record ServicePrincipal(String name) {
}
