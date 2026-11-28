package com.medinexus.medicore.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.security.jwt.TestTokens;
import com.medinexus.medicore.security.jwt.UserClaims;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NIT-337: a whole conversation over real HTTP against the real "messaging" schema. Messaging runs for real; Identity
 * is a small HTTP server in this test that answers I1/I2 (docs/architecture.md §13) from synthetic accounts, so
 * {@code IdentityClient}, the service token, the timeouts and the JSON all run for real too. Requests carry the same
 * JWT cookie Identity's login sets.
 *
 * <p>Needs the Messaging database (DB_URL, MESSAGING_DB_PASSWORD); skipped without it. Account ids are random and
 * high, and the test deletes its messages afterwards, so it can also run against a developer database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "MESSAGING_DB_PASSWORD", matches = ".+")
class MessagingFlowIntegrationTest {

    /** One request the fake Identity received. */
    private record IdentityCall(String query, String authorization) {
    }

    private record Account(long id, String displayName, Role role) {
    }

    private static final Map<Long, Account> ACCOUNTS = new ConcurrentHashMap<>();
    private static final List<IdentityCall> IDENTITY_CALLS = new CopyOnWriteArrayList<>();
    private static HttpServer identity;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private ObjectMapper json;

    private final List<Long> created = new ArrayList<>();

    @DynamicPropertySource
    static void identityUrl(DynamicPropertyRegistry registry) throws IOException {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/internal/users", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            IDENTITY_CALLS.add(new IdentityCall(query, exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = identityAnswer(query).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        identity.start();
        registry.add("medinexus.identity.url", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    }

    /** I1 by id or I2 by role, from {@link #ACCOUNTS}, in Identity's order. */
    private static String identityAnswer(String query) {
        String[] parameter = query.split("=", 2);
        List<String> values = Arrays.asList(parameter[1].split(","));
        List<Account> users = ACCOUNTS.values().stream()
                .filter(account -> parameter[0].equals("ids") ? values.contains(String.valueOf(account.id()))
                        : values.contains(account.role().name()))
                .sorted((a, b) -> parameter[0].equals("ids") ? Long.compare(a.id(), b.id())
                        : a.displayName().compareToIgnoreCase(b.displayName()))
                .toList();
        return "{\"users\":[" + users.stream()
                .map(user -> "{\"id\":%d,\"displayName\":\"%s\",\"role\":\"%s\"}".formatted(user.id(),
                        user.displayName(), user.role()))
                .collect(Collectors.joining(",")) + "]}";
    }

    @AfterAll
    static void stopIdentity() {
        identity.stop(0);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM messages WHERE sender_id = ? OR recipient_id = ?", id, id);
            ACCOUNTS.remove(id);
        }
    }

    private long account(Role role, String displayName) {
        long id = 8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        ACCOUNTS.put(id, new Account(id, displayName, role));
        created.add(id);
        return id;
    }

    private HttpResponse<String> call(String method, String path, long accountId, Role role, String body)
            throws Exception {
        String token = TestTokens.tokens().userToken(
                new UserClaims(accountId, "flow-" + accountId + "@example.test", role, null, null, null, null));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Cookie", TestTokens.COOKIE + "=" + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    /** I1/I2 calls made since {@code from}. */
    private List<IdentityCall> identityCallsSince(int from) {
        return List.copyOf(IDENTITY_CALLS.subList(from, IDENTITY_CALLS.size()));
    }

    @Test
    void patientAndDoctorHaveAWholeConversation() throws Exception {
        long maja = account(Role.PATIENT, "Maja Flow");
        long ebba = account(Role.PATIENT, "Ebba Flow");
        long doctor = account(Role.DOCTOR, "Aaron Flowdoctor");
        int callsBefore = IDENTITY_CALLS.size();

        // 1. Maja finds the doctor in the recipient list (I2).
        JsonNode recipients = ok(call("GET", "/api/messages/recipients", maja, Role.PATIENT, null), 200);
        assertThat(recipients.findValuesAsString("displayName")).contains("Aaron Flowdoctor");

        // 2. Maja writes to him (I1 checks that the recipient is a doctor or staff member).
        JsonNode sent = ok(call("POST", "/api/messages", maja, Role.PATIENT,
                "{\"recipientId\":" + doctor + ",\"content\":\"Can I get a new prescription?\"}"), 201);
        long questionId = sent.get("id").asLong();

        // 3. The doctor's inbox shows Maja by name, after one I1 call for the whole list.
        int beforeInbox = IDENTITY_CALLS.size();
        JsonNode inbox = ok(call("GET", "/api/messages/inbox", doctor, Role.DOCTOR, null), 200);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("patientId").asLong()).isEqualTo(maja);
        assertThat(inbox.get(0).get("displayName").asString()).isEqualTo("Maja Flow");
        assertThat(inbox.get(0).get("messageCount").asLong()).isEqualTo(1);
        assertThat(identityCallsSince(beforeInbox)).singleElement()
                .satisfies(identityCall -> assertThat(identityCall.query()).isEqualTo("ids=" + maja));

        // 4. He opens the thread and replies.
        JsonNode doctorThread = ok(call("GET", "/api/messages/inbox/" + maja, doctor, Role.DOCTOR, null), 200);
        assertThat(doctorThread.findValuesAsString("content")).containsExactly("Can I get a new prescription?");
        JsonNode reply = ok(call("POST", "/api/messages/inbox/" + maja + "/reply", doctor, Role.DOCTOR,
                "{\"content\":\"Yes, it is ready at the pharmacy.\"}"), 201);
        long replyId = reply.get("id").asLong();

        // 5. Maja sees one conversation with two messages, and both messages in order.
        int beforeConversations = IDENTITY_CALLS.size();
        JsonNode conversations = ok(call("GET", "/api/messages/conversations", maja, Role.PATIENT, null), 200);
        assertThat(conversations).hasSize(1);
        assertThat(conversations.get(0).get("recipientId").asLong()).isEqualTo(doctor);
        assertThat(conversations.get(0).get("displayName").asString()).isEqualTo("Aaron Flowdoctor");
        assertThat(conversations.get(0).get("role").asString()).isEqualTo("DOCTOR");
        assertThat(conversations.get(0).get("messageCount").asLong()).isEqualTo(2);
        assertThat(identityCallsSince(beforeConversations)).hasSize(1);
        JsonNode thread = ok(call("GET", "/api/messages/conversations/" + doctor, maja, Role.PATIENT, null), 200);
        assertThat(thread).hasSize(2);
        assertThat(thread.get(0).get("id").asLong()).isEqualTo(questionId);
        assertThat(thread.get(0).get("senderId").asLong()).isEqualTo(maja);
        assertThat(thread.get(1).get("id").asLong()).isEqualTo(replyId);
        assertThat(thread.get(1).get("senderId").asLong()).isEqualTo(doctor);
        assertThat(thread.get(1).get("content").asString()).isEqualTo("Yes, it is ready at the pharmacy.");

        // Ebba sees nothing of it, and the doctor can't start a conversation with her.
        assertThat(ok(call("GET", "/api/messages/conversations", ebba, Role.PATIENT, null), 200)).isEmpty();
        assertThat(ok(call("GET", "/api/messages/conversations/" + doctor, ebba, Role.PATIENT, null), 200)).isEmpty();
        ok(call("POST", "/api/messages/inbox/" + ebba + "/reply", doctor, Role.DOCTOR, "{\"content\":\"Hi\"}"), 403);

        // The rows in messaging.messages are exactly the two messages.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE sender_id IN (?, ?, ?)", Long.class,
                maja, ebba, doctor)).isEqualTo(2);

        // Every call to Identity carried a fresh service token signed as "messaging".
        List<IdentityCall> calls = identityCallsSince(callsBefore);
        assertThat(calls).isNotEmpty().allSatisfy(identityCall -> {
            assertThat(identityCall.authorization()).startsWith("Bearer ");
            Jwt token = decoder.decode(identityCall.authorization().substring("Bearer ".length()));
            assertThat(token.getSubject()).isEqualTo("messaging");
            assertThat(token.getClaimAsString("role")).isEqualTo("SERVICE");
        });
    }

    @Test
    void aRecipientIdentityDoesNotKnowIsNotWrittenTo() throws Exception {
        long maja = account(Role.PATIENT, "Maja Flow");
        long unknown = 8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        created.add(unknown);
        ok(call("POST", "/api/messages", maja, Role.PATIENT,
                "{\"recipientId\":" + unknown + ",\"content\":\"Anyone there?\"}"), 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE sender_id = ?", Long.class, maja))
                .isZero();
    }
}
