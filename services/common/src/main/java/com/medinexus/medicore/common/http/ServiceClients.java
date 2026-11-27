package com.medinexus.medicore.common.http;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.medinexus.medicore.security.jwt.JwtTokenService;

/**
 * Builds the HTTP clients one MediCore service uses to call another's {@code /internal/**} endpoints (EPIC-02,
 * docs/architecture.md D2–D6). Every client has the same two guarantees:
 * <ul>
 *   <li><b>Timeouts</b>: connecting may take at most {@link #CONNECT_TIMEOUT}, waiting for the answer at most
 *       {@link #READ_TIMEOUT}. A slow or dead service therefore fails fast instead of hanging the caller; the
 *       failure surfaces as a {@code ResourceAccessException}, which {@code GlobalExceptionHandler} turns into 503.</li>
 *   <li><b>Service token</b>: each request gets a fresh {@code Authorization: Bearer} service token signed as this
 *       service, so the receiving {@code /internal/**} rule accepts it.</li>
 * </ul>
 * No retries: a retried write could be applied twice.
 */
public class ServiceClients {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final JwtTokenService tokens;
    private final String serviceName;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ServiceClients(JwtTokenService tokens, String serviceName) {
        this(tokens, serviceName, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /** With other timeouts; tests use this to keep a "hanging service" test short. */
    public ServiceClients(JwtTokenService tokens, String serviceName, Duration connectTimeout, Duration readTimeout) {
        this.tokens = tokens;
        this.serviceName = serviceName;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        tokens.serviceToken(serviceName); // fails at startup if the name isn't a valid service name
    }

    /** A client for the service at {@code baseUrl}, e.g. {@code http://identity:8080}. */
    public RestClient forService(String baseUrl) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requests.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requests)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.serviceToken(serviceName));
                    return execution.execute(request, body);
                })
                .build();
    }
}
