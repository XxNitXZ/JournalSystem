package com.medinexus.medicore.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.medinexus.medicore.security.jwt.JwtSecurityConfig;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.sun.net.httpserver.HttpServer;

/** Timeouts and the service token of the service-to-service client, against a local fake service (NIT-328). */
class ServiceClientsTest {

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) server.stop(0);
    }

    private String serve(int status, boolean hang, AtomicReference<String> authorization) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ping", exchange -> {
            if (authorization != null) authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (hang) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ServiceClients clients(Duration readTimeout) {
        return new ServiceClients(TestTokens.tokens(), "messaging", Duration.ofSeconds(1), readTimeout);
    }

    @Test
    void everyCallCarriesAFreshServiceTokenSignedAsThisService() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        String base = serve(200, false, authorization);

        String body = clients(Duration.ofSeconds(2)).forService(base).get().uri("/internal/ping").retrieve()
                .body(String.class);

        assertThat(body).contains("ok");
        assertThat(authorization.get()).startsWith("Bearer ");
        var token = new JwtSecurityConfig().jwtDecoder(TestTokens.properties())
                .decode(authorization.get().substring("Bearer ".length()));
        assertThat(token.getSubject()).isEqualTo("messaging");
        assertThat(token.getClaimAsString("role")).isEqualTo("SERVICE");
    }

    @Test
    void aServiceThatNeverAnswersFailsAfterTheReadTimeoutInsteadOfHanging() throws Exception {
        String base = serve(200, true, null);
        long started = System.nanoTime();

        assertThatThrownBy(() -> clients(Duration.ofMillis(300)).forService(base).get().uri("/internal/ping")
                .retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMillis).as("gave up after about the read timeout").isBetween(250L, 2_000L);
    }

    @Test
    void anUnreachableServiceFailsFast() {
        // Nothing listens on port 1.
        assertThatThrownBy(() -> clients(Duration.ofSeconds(2)).forService("http://127.0.0.1:1").get()
                .uri("/internal/ping").retrieve().body(String.class)).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void anErrorAnswerIsReportedToTheCaller() throws Exception {
        String base = serve(500, false, null);
        assertThatThrownBy(() -> clients(Duration.ofSeconds(2)).forService(base).get().uri("/internal/ping")
                .retrieve().body(String.class)).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void anInvalidServiceNameStopsStartup() {
        assertThatThrownBy(() -> new ServiceClients(TestTokens.tokens(), "MediNexus"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
