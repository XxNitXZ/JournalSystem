package com.medinexus.medicore.fhir.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;

/**
 * Minimal stateful FHIR R4 test server on 127.0.0.1 (random port) for synthetic data only; nothing is ever
 * sent to an external FHIR server. Supported interactions:
 * <ul>
 *   <li>{@code POST [base]/{Type}}: stores the resource with a server-assigned ID ({@code local-1}, ...) and
 *       version 1, answers 201 with a {@code Location} header and no body;</li>
 *   <li>{@code GET [base]/{Type}/{id}}: the stored resource, or 404 with an OperationOutcome;</li>
 *   <li>{@code GET [base]/{Type}?subject=...}: a single searchset page of stored resources of that type whose
 *       {@code subject.reference} equals the parameter exactly, in creation order (other parameters are ignored).</li>
 * </ul>
 * Resources that MediCore cannot create (such as Patient and Practitioner) are added with {@link #seed}.
 * Every other method is answered with 405. Each request is recorded as {@code "METHOD /path?query"}.
 */
public final class LocalFhirServer implements AutoCloseable {

    private static final FhirContext CONTEXT = FhirContext.forR4();

    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final HttpServer server;
    private final String baseUrl;

    private LocalFhirServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    /** Starts a new, empty server. Close it after use. */
    public static LocalFhirServer start() throws IOException {
        return new LocalFhirServer();
    }

    /** Base URL for {@code medinexus.fhir.base-url}, for example {@code http://127.0.0.1:54321/fhir}. */
    public String baseUrl() {
        return baseUrl;
    }

    /** Stores a resource under its own type and logical ID with version 1, as if it already existed. */
    public synchronized void seed(Resource resource) {
        String type = resource.fhirType();
        String id = resource.getIdElement().getIdPart();
        if (id == null) throw new IllegalArgumentException("A seeded resource needs a logical ID");
        Resource copy = resource.copy();
        stamp(copy, id);
        resources.put(type + "/" + id, copy);
    }

    /** A copy of the stored resource, or null. */
    public synchronized Resource stored(String type, String id) {
        Resource resource = resources.get(type + "/" + id);
        return resource == null ? null : resource.copy();
    }

    /** Number of stored resources of the given type. */
    public synchronized long count(String type) {
        return resources.keySet().stream().filter(key -> key.startsWith(type + "/")).count();
    }

    /** All requests received so far, in order, as {@code "METHOD /path?query"}. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath().substring("/fhir/".length());
            requests.add(method + " " + exchange.getRequestURI());
            String[] parts = path.split("/");
            if (method.equals("POST") && parts.length == 1) {
                create(exchange, parts[0]);
            } else if (method.equals("GET") && parts.length == 1) {
                search(exchange, parts[0]);
            } else if (method.equals("GET") && parts.length == 2) {
                Resource resource = stored(parts[0], parts[1]);
                if (resource == null) outcome(exchange, 404, OperationOutcome.IssueType.NOTFOUND);
                else send(exchange, 200, resource);
            } else {
                outcome(exchange, 405, OperationOutcome.IssueType.NOTSUPPORTED);
            }
        }
    }

    private void create(HttpExchange exchange, String type) throws IOException {
        Resource resource = (Resource) CONTEXT.newJsonParser().parseResource(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!type.equals(resource.fhirType())) {
            outcome(exchange, 400, OperationOutcome.IssueType.INVALID);
            return;
        }
        String id = "local-" + ids.incrementAndGet();
        synchronized (this) {
            stamp(resource, id);
            resources.put(type + "/" + id, resource);
        }
        exchange.getResponseHeaders().set("Location", baseUrl + "/" + type + "/" + id + "/_history/1");
        exchange.sendResponseHeaders(201, -1);
    }

    private void search(HttpExchange exchange, String type) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String subject = query == null ? null : Arrays.stream(query.split("&"))
                .map(parameter -> URLDecoder.decode(parameter, StandardCharsets.UTF_8))
                .filter(parameter -> parameter.startsWith("subject="))
                .map(parameter -> parameter.substring("subject=".length())).findFirst().orElse(null);
        Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
        List<Resource> matches = new ArrayList<>();
        synchronized (this) {
            resources.forEach((key, resource) -> {
                if (key.startsWith(type + "/") && subject != null && subject.equals(subjectReference(resource))) {
                    matches.add(resource.copy());
                }
            });
        }
        matches.forEach(resource -> bundle.addEntry().setResource(resource)
                .getSearch().setMode(Bundle.SearchEntryMode.MATCH));
        bundle.setTotal(matches.size());
        send(exchange, 200, bundle);
    }

    private static String subjectReference(Resource resource) {
        var property = resource.getNamedProperty("subject");
        if (property == null || !property.hasValues()) return null;
        Base value = property.getValues().getFirst();
        return value instanceof Reference reference ? reference.getReference() : null;
    }

    private static void stamp(Resource resource, String id) {
        resource.setId(id);
        resource.getMeta().setVersionId("1").setLastUpdated(new Date());
    }

    private void outcome(HttpExchange exchange, int status, OperationOutcome.IssueType type) throws IOException {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR).setCode(type);
        send(exchange, status, outcome);
    }

    private void send(HttpExchange exchange, int status, Resource resource) throws IOException {
        byte[] bytes = CONTEXT.newJsonParser().encodeResourceToString(resource).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
