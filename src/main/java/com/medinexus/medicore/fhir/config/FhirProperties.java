package com.medinexus.medicore.fhir.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the external FHIR server ({@code medinexus.fhir.*}).
 *
 * <p>The base URL must be an absolute http(s) URL with a host; any host, port
 * and path are accepted (for example a local test server). A trailing slash is
 * removed. All timeouts must be positive:
 * <ul>
 *   <li>{@code connect-timeout}: establishing the TCP connection,</li>
 *   <li>{@code socket-timeout}: maximum inactivity while reading the response,</li>
 *   <li>{@code connection-request-timeout}: waiting for a free pooled connection.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "medinexus.fhir")
public record FhirProperties(String baseUrl, Duration connectTimeout, Duration socketTimeout,
        Duration connectionRequestTimeout) {

    public FhirProperties {
        baseUrl = normalizeBaseUrl(baseUrl);
        requirePositive("connect-timeout", connectTimeout);
        requirePositive("socket-timeout", socketTimeout);
        requirePositive("connection-request-timeout", connectionRequestTimeout);
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("medinexus.fhir.base-url must not be empty");
        }
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("medinexus.fhir.base-url is not a valid URL: " + value, exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("medinexus.fhir.base-url must start with http:// or https://: " + value);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("medinexus.fhir.base-url must contain a host: " + value);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("medinexus.fhir.base-url must not contain a query or fragment: " + value);
        }
        return trimmed;
    }

    private static void requirePositive(String name, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("medinexus.fhir." + name + " must be a positive duration");
        }
    }
}
