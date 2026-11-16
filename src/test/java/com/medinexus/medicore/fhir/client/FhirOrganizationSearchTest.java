package com.medinexus.medicore.fhir.client;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.medinexus.medicore.fhir.config.FhirClientConfig;
import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.sun.net.httpserver.HttpServer;

class FhirOrganizationSearchTest {
    private HttpServer server;
    private String base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private Function<String, String> response = query -> bundle("", "");
    private int errorOnRequest = -1;
    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            int status = requests.size() == errorOnRequest ? 503 : 200;
            String body = status == 200 ? response.apply(exchange.getRequestURI().getRawQuery())
                    : "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"processing\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }
    @AfterEach void stop() { server.stop(0); }
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(FhirClientConfig.class, FhirRequestExecutor.class,
                FhirOrganizationClient.class, OrganizationMapper.class).withPropertyValues("medinexus.fhir.base-url=" + base,
                        "medinexus.fhir.connect-timeout=2s", "medinexus.fhir.socket-timeout=5s", "medinexus.fhir.connection-request-timeout=2s");
    }
    private String organization(String id, String name, String active) {
        return "{\"resource\":{\"resourceType\":\"Organization\",\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"active\":" + active + "}}";
    }
    private String bundle(String entries, String next) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[" + entries + "]"
                + (next.isEmpty() ? "" : ",\"link\":[{\"relation\":\"next\",\"url\":\"" + next + "\"}]") + "}";
    }
    private void invalid() {
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class).findActiveOrganizations())
                .isInstanceOfSatisfying(FhirServerException.class, error -> assertThat(error.type()).isEqualTo(FhirServerException.Type.INVALID_RESPONSE)));
    }
    @Test void filtersAndSortsWithoutMergingDuplicateNames() {
        response = query -> bundle(organization("b", "Ward", "true") + "," + organization("a", "Ward", "true")
                + "," + organization("c", "alpha", "true") + "," + organization("d", "Inactive", "false")
                + "," + organization("e", " ", "true")
                + "," + organization("f", "No active flag", "true").replace(",\"active\":true", ""), "");
        context().run(ctx -> {
            var options = ctx.getBean(FhirOrganizationClient.class).findActiveOrganizations();
            assertThat(options).extracting(OrganizationDto::id).containsExactly("c", "a", "b");
            assertThat(options).extracting(OrganizationDto::name).containsExactly("alpha", "Ward", "Ward");
            assertThatThrownBy(() -> options.clear()).isInstanceOf(UnsupportedOperationException.class);
        });
        assertThat(URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8))
                .startsWith("GET /fhir/Organization?").contains("active=true", "_count=100");
        assertThat(requests).hasSize(1);
    }
    @Test void emptySearchIsValid() {
        context().run(ctx -> assertThat(ctx.getBean(FhirOrganizationClient.class).findActiveOrganizations()).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"query", "relative", "absolute", "root", "absolute-root", "query-root"})
    void followsSafeEndpointAndKthRootPagingLinks(String style) {
        String next = switch (style) {
            case "query" -> "?page=2";
            case "relative" -> "/fhir/Organization?page=2";
            case "absolute" -> base + "/Organization?page=2";
            case "absolute-root" -> base + "?_getpages=token&amp;_getpagesoffset=100";
            default -> "/fhir?_getpages=token&amp;_getpagesoffset=100";
        };
        // Links are JSON strings, not HTML; encode ampersands literally.
        next = next.replace("&amp;", "&");
        String firstNext = next;
        response = query -> {
            if (query.contains("_getpagesoffset=200")) return bundle(organization("c", "Gamma", "true"), "");
            if (query.contains("page=2") || query.contains("_getpagesoffset=100")) {
                return bundle(organization("a", "Alpha", "true"), style.equals("query-root") ? "?_getpages=token&_getpagesoffset=200" : "");
            }
            return bundle(organization("z", "Zulu", "true"), firstNext);
        };
        context().run(ctx -> assertThat(ctx.getBean(FhirOrganizationClient.class).findActiveOrganizations())
                .extracting(OrganizationDto::id).containsExactlyElementsOf(style.equals("query-root") ? List.of("a", "c", "z") : List.of("a", "z")));
        assertThat(requests).hasSize(style.equals("query-root") ? 3 : 2).allMatch(request -> request.startsWith("GET "));
    }
    @ParameterizedTest @ValueSource(strings = {"http://other.test/fhir?_getpages=token", "/outside?_getpages=token",
            "/fhir/Patient?page=2", "/fhir/Organization/a", "/fhir/$expunge", "/fhir?page=2", "/fhir?_getpages=",
            "/fhir/../outside", "/fhir/%2e%2e/outside", "http://user@127.0.0.1/fhir", "?page=2#fragment"})
    void rejectsUnsafeLinksBeforeRequestingThem(String next) {
        response = query -> bundle("", next); invalid(); assertThat(requests).hasSize(1);
    }
    @Test void detectsRepeatedLinksAndDuplicateIds() {
        response = query -> bundle("", "?_getpages=token"); invalid(); assertThat(requests).hasSize(2);
        response = query -> bundle(organization("a", "Provider", "true") + "," + organization("a", "Provider", "true"), ""); invalid();
    }
    @ParameterizedTest @ValueSource(strings = {"parse", "bundle", "resource", "id", "missingId", "mode", "ambiguous"})
    void rejectsMalformedResponses(String defect) {
        String entry = organization("a", "Provider", "true");
        String body = switch (defect) {
            case "parse" -> "not-json";
            case "bundle" -> bundle("", "").replace("searchset", "collection");
            case "resource" -> bundle(entry.replace("Organization", "Location"), "");
            case "id" -> bundle(entry.replace("\"id\":\"a\"", "\"id\":\"bad_id\""), "");
            case "missingId" -> bundle(entry.replace("\"id\":\"a\",", ""), "");
            case "mode" -> bundle(entry.replace("{\"resource\":", "{\"search\":{\"mode\":\"include\"},\"resource\":"), "");
            case "ambiguous" -> bundle("", "?page=2").replace("\"link\":[", "\"link\":[{\"relation\":\"next\",\"url\":\"?page=3\"},");
            default -> throw new AssertionError();
        };
        response = query -> body; invalid();
    }
    @Test void boundsPagesAndExaminedEntriesIncludingFilteredResources() {
        response = query -> bundle("", "?page=" + requests.size()); invalid(); assertThat(requests).hasSize(10);
        String entries = IntStream.range(0, 1000).mapToObj(i -> organization("id-" + i, " ", "false")).collect(Collectors.joining(","));
        response = query -> bundle(entries, "");
        context().run(ctx -> assertThat(ctx.getBean(FhirOrganizationClient.class).findActiveOrganizations()).isEmpty());
        response = query -> bundle(entries + "," + organization("extra", "Provider", "true"), ""); invalid();
    }
    @Test void missingPagesAndMalformedLaterPagesDoNotReturnPartialResults() {
        response = query -> bundle(organization("a", "Provider", "true"), "")
                .replace("\"entry\":", "\"total\":2,\"entry\":");
        invalid();
        response = query -> query.contains("page=2") ? "not-json"
                : bundle(organization("a", "Provider", "true"), "?page=2");
        invalid();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void upstreamFailuresNeverReturnPartialOptions(int requestNumber) {
        errorOnRequest = requestNumber;
        response = query -> bundle(organization("a", "Provider", "true"), "?page=2");
        context().run(ctx -> assertThatThrownBy(() -> ctx.getBean(FhirOrganizationClient.class).findActiveOrganizations())
                .isInstanceOfSatisfying(FhirServerException.class, error -> assertThat(error.type()).isEqualTo(FhirServerException.Type.UNAVAILABLE)));
    }
}
