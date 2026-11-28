package com.medinexus.medicore.identityapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.common.http.ServiceClients;
import com.medinexus.medicore.identityapi.IdentityClient.DirectoryUser;
import com.medinexus.medicore.security.jwt.JwtSecurityConfig;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.sun.net.httpserver.HttpServer;

/** The Identity API client (I1–I2, docs/architecture.md §13) against a local fake Identity. */
class IdentityClientTest {

    /** Every request the fake received: decoded query and Authorization header. */
    private record Call(String query, String authorization) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile boolean hang;
    private HttpServer server;
    private IdentityClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/users", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            calls.add(new Call(query, exchange.getRequestHeaders().getFirst("Authorization")));
            if (hang) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = (status == 200 ? answer(query) : "{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        ServiceClients clients = new ServiceClients(TestTokens.tokens(), "messaging", Duration.ofSeconds(1),
                Duration.ofMillis(400));
        client = new IdentityClient(clients, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    /** Knows every even id as a patient; I2 answers with one doctor. */
    private static String answer(String query) {
        if (query.startsWith("roles=")) {
            return "{\"users\":[{\"id\":2,\"displayName\":\"Zoe Smith\",\"role\":\"DOCTOR\"}]}";
        }
        String users = java.util.Arrays.stream(query.substring("ids=".length()).split(","))
                .map(Long::valueOf).filter(id -> id % 2 == 0)
                .map(id -> "{\"id\":" + id + ",\"displayName\":\"Patient #" + id + "\",\"role\":\"PATIENT\"}")
                .collect(Collectors.joining(","));
        return "{\"users\":[" + users + "]}";
    }

    @Test
    void i1ReturnsTheKnownAccountsAndSendsAServiceToken() {
        Map<Long, DirectoryUser> found = client.usersById(List.of(3L, 4L, 4L, 6L));
        assertThat(found).containsOnlyKeys(4L, 6L);
        assertThat(found.get(4L)).isEqualTo(new DirectoryUser(4L, "Patient #4", Role.PATIENT));
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.query()).isEqualTo("ids=3,4,6");
            assertThat(call.authorization()).startsWith("Bearer ");
            String token = call.authorization().substring("Bearer ".length());
            var decoded = new JwtSecurityConfig().jwtDecoder(TestTokens.properties()).decode(token);
            assertThat(decoded.getSubject()).isEqualTo("messaging");
            assertThat(decoded.getClaimAsString("role")).isEqualTo("SERVICE");
        });
    }

    @Test
    void i1SplitsLongListsAndAsksNothingForAnEmptyOne() {
        assertThat(client.usersById(List.of())).isEmpty();
        assertThat(calls).isEmpty();
        List<Long> ids = LongStream.rangeClosed(1, 450).boxed().toList();
        assertThat(client.usersById(ids)).hasSize(225);
        assertThat(calls).hasSize(3);
    }

    @Test
    void oneUserIsEmptyWhenUnknown() {
        assertThat(client.user(4L)).contains(new DirectoryUser(4L, "Patient #4", Role.PATIENT));
        assertThat(client.user(5L)).isEmpty();
    }

    @Test
    void i2AsksForTheRoles() {
        assertThat(client.usersWithRoles(Role.DOCTOR, Role.STAFF))
                .containsExactly(new DirectoryUser(2L, "Zoe Smith", Role.DOCTOR));
        assertThat(calls).singleElement().satisfies(call -> assertThat(call.query()).isEqualTo("roles=DOCTOR,STAFF"));
    }

    @Test
    void identityErrorsAreThrown() {
        status = 500;
        assertThatThrownBy(() -> client.user(4L)).isInstanceOf(RestClientResponseException.class);
        status = 403;
        assertThatThrownBy(() -> client.usersWithRoles(Role.DOCTOR)).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void aHangingIdentityTimesOut() {
        hang = true;
        assertThatThrownBy(() -> client.user(4L)).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void anUnreachableIdentityIsAResourceAccessException() {
        server.stop(0);
        assertThatThrownBy(() -> client.user(4L)).isInstanceOf(ResourceAccessException.class);
    }
}
