package com.medinexus.medicore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NIT-329: the Identity service starts against Postgres as identity_svc, Flyway creates its tables in the
 * "identity" schema, and the health check is UP. Needs a database prepared with init-schemas.sql and the variables
 * DB_URL and IDENTITY_DB_PASSWORD; skipped without them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "IDENTITY_DB_PASSWORD", matches = ".+")
class IdentityApplicationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void startsHealthyAsTheIdentityUser() throws Exception {
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("identity_svc");
    }

    @Test
    void flywayCreatedIdentitysTablesInItsOwnSchema() {
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'identity' ORDER BY 1",
                String.class)).containsExactly("flyway_schema_history", "journal_access_requests", "users");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM identity.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("1");
    }

    @Test
    void otherServicesDataIsOutOfReach() {
        // identity_svc has no rights on another service's schema (NIT-324). PostgreSQL refuses at the schema level,
        // so this holds whether or not the other service has created its tables yet. Spring wraps PostgreSQL's
        // error; the reason is in the root cause.
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM clinical.registered_patients", Long.class))
                .rootCause().hasMessageContaining("permission denied for schema clinical");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM messaging.messages", Long.class))
                .rootCause().hasMessageContaining("permission denied for schema messaging");
    }

    @Test
    void everythingButHealthIsClosedForNow() throws Exception {
        HttpResponse<String> me = get("/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.body()).contains("\"status\":401");
    }
}
