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
import org.hl7.fhir.r4.model.Identifier;
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
 *       version 1, answers 201 with a {@code Location} header and no body. A conditional create
 *       ({@code If-None-Exist: [Type?]identifier=system|value}, the only supported condition) creates nothing
 *       and answers 200 with the existing resource's {@code Location} on one match, or 412 on several;</li>
 *   <li>{@code GET [base]/{Type}/{id}}: the stored resource, or 404 with an OperationOutcome;</li>
 *   <li>{@code GET [base]/{Type}?subject=...}: a single searchset page of stored resources of that type whose
 *       {@code subject.reference} equals the parameter exactly, in creation order (other parameters are ignored);</li>
 *   <li>{@code POST [base]/{Type}/_search} with a form body ({@code _id}, {@code name}, {@code identifier},
 *       {@code _count}) and further pages via {@code GET [base]?_getpages=...}, like HAPI (see formSearch).</li>
 * </ul>
 * Resources that MediCore cannot create (such as Patient and Practitioner) are added with {@link #seed}.
 * Every other method is answered with 405. Each request is recorded as {@code "METHOD /path?query"}.
 */
public final class LocalFhirServer implements AutoCloseable {

    private static final FhirContext CONTEXT = FhirContext.forR4();

    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> conditions = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final HttpServer server;
    private final String baseUrl;

    private LocalFhirServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/", this::handle);
        // The base itself only serves further pages of a search (HAPI's ?_getpages links).
        server.createContext("/fhir", this::page);
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

    /** Removes a stored resource, as if it had been deleted on the server; later reads get 404. */
    public synchronized void remove(String type, String id) {
        resources.remove(type + "/" + id);
    }

    /** Number of stored resources of the given type. */
    public synchronized long count(String type) {
        return resources.keySet().stream().filter(key -> key.startsWith(type + "/")).count();
    }

    /** All requests received so far, in order, as {@code "METHOD /path?query"}. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** The {@code If-None-Exist} header of every conditional create received so far, in order. */
    public List<String> conditionalCreates() {
        return List.copyOf(conditions);
    }

    /** The decoded form body of every {@code POST [base]/{Type}/_search} received so far, in order. */
    public List<Map<String, List<String>>> searchForms() {
        return List.copyOf(forms);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** The {@code If-Match} header of every {@code PUT} received so far, in order ("" when absent). */
    public List<String> ifMatches() {
        return List.copyOf(ifMatches);
    }

    /** While true, every {@code PUT} answers 503, as during a FHIR server outage. */
    public void updatesUnavailable(boolean unavailable) {
        updatesUnavailable = unavailable;
    }

    /** Delays every {@code PUT} by this many milliseconds (to provoke client timeouts); 0 for none. */
    public void updateDelayMillis(long millis) {
        updateDelayMillis = millis;
    }

    /**
     * Simulates a change by someone else: stores the resource under its ID with the next version, as an update would.
     */
    public synchronized void changeConcurrently(Resource resource) {
        String key = resource.fhirType() + "/" + resource.getIdElement().getIdPart();
        Resource current = resources.get(key);
        if (current == null) throw new IllegalArgumentException("No such resource");
        Resource copy = resource.copy();
        copy.setId(resource.getIdElement().getIdPart());
        copy.getMeta().setVersionId(String.valueOf(Integer.parseInt(current.getMeta().getVersionId()) + 1))
                .setLastUpdated(new Date());
        resources.put(key, copy);
    }

    private final List<String> ifMatches = new CopyOnWriteArrayList<>();
    private volatile boolean updatesUnavailable;
    private volatile long updateDelayMillis;

    /** While true, every {@code POST [base]/{Type}/_search} answers 503, as during a FHIR server outage. */
    public void searchesUnavailable(boolean unavailable) {
        searchesUnavailable = unavailable;
    }

    // ---- POST [base]/{Type}/_search with HAPI-style paging ----

    private volatile boolean searchesUnavailable;
    private final List<Map<String, List<String>>> forms = new CopyOnWriteArrayList<>();
    private final Map<String, List<Resource>> pagedSearches = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger searchIds = new AtomicInteger();

    /**
     * Form-encoded search: {@code _id} (comma-separated IDs), repeated {@code name} (each must match the start of
     * a family name, given name or name text, ignoring case and accents; all must match), {@code identifier}
     * ({@code system|value}, exact; several comma-separated are OR) and {@code _count} (default 100). Any other parameter gives 400. Results are
     * in creation order; further pages are linked as {@code [base]?_getpages=...&_getpagesoffset=...}, as HAPI does.
     */
    private void formSearch(HttpExchange exchange, String type) throws IOException {
        if (searchesUnavailable) {
            exchange.getRequestBody().readAllBytes();
            outcome(exchange, 503, OperationOutcome.IssueType.TRANSIENT);
            return;
        }
        Map<String, List<String>> form = new LinkedHashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            if (pair.isEmpty()) continue;
            String[] keyValue = pair.split("=", 2);
            form.computeIfAbsent(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8), key -> new ArrayList<>())
                    .add(keyValue.length < 2 ? "" : URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
        }
        forms.add(form);
        if (!List.of("_id", "name", "identifier", "_count").containsAll(form.keySet())) {
            outcome(exchange, 400, OperationOutcome.IssueType.NOTSUPPORTED);
            return;
        }
        List<String> idFilter = form.containsKey("_id")
                ? form.get("_id").stream().flatMap(value -> Arrays.stream(value.split(","))).toList() : null;
        List<String> names = form.getOrDefault("name", List.of()).stream().map(LocalFhirServer::normalize).toList();
        // identifier=system|value, or several comma-separated (FHIR token OR), each matched exactly.
        List<String[]> identifiers = form.containsKey("identifier")
                ? Arrays.stream(form.get("identifier").getFirst().split(",")).map(token -> token.split("\\|", 2)).toList()
                : null;
        int count = form.containsKey("_count") ? Integer.parseInt(form.get("_count").getFirst()) : 100;
        List<Resource> matches = new ArrayList<>();
        synchronized (this) {
            resources.forEach((key, resource) -> {
                String id = key.substring(key.indexOf('/') + 1);
                if (key.startsWith(type + "/") && (idFilter == null || idFilter.contains(id))
                        && names.stream().allMatch(name -> nameMatches(resource, name))
                        && (identifiers == null || identifiers.stream().anyMatch(token -> token.length == 2
                                && hasIdentifier(resource, token[0], token[1])))) {
                    matches.add(resource.copy());
                }
            });
        }
        String searchId = "local-search-" + searchIds.incrementAndGet();
        pagedSearches.put(searchId, matches);
        sendPage(exchange, searchId, 0, count);
    }

    /** {@code GET [base]?_getpages=...&_getpagesoffset=...&_count=...}. */
    private void page(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            Map<String, String> query = new LinkedHashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            for (String pair : raw == null ? new String[0] : raw.split("&")) {
                String[] keyValue = pair.split("=", 2);
                query.put(keyValue[0], keyValue.length < 2 ? "" : URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
            }
            if (!exchange.getRequestMethod().equals("GET") || !pagedSearches.containsKey(query.get("_getpages"))) {
                outcome(exchange, 410, OperationOutcome.IssueType.NOTFOUND);
                return;
            }
            sendPage(exchange, query.get("_getpages"), Integer.parseInt(query.get("_getpagesoffset")),
                    Integer.parseInt(query.get("_count")));
        }
    }

    private void sendPage(HttpExchange exchange, String searchId, int offset, int count) throws IOException {
        List<Resource> matches = pagedSearches.get(searchId);
        Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(matches.size());
        matches.subList(Math.min(offset, matches.size()), Math.min(offset + count, matches.size()))
                .forEach(resource -> bundle.addEntry().setResource(resource).getSearch().setMode(Bundle.SearchEntryMode.MATCH));
        if (offset + count < matches.size()) {
            bundle.addLink().setRelation("next").setUrl(baseUrl + "?_getpages=" + searchId + "&_getpagesoffset="
                    + (offset + count) + "&_count=" + count);
        }
        send(exchange, 200, bundle);
    }

    private static boolean nameMatches(Resource resource, String prefix) {
        if (!(resource instanceof org.hl7.fhir.r4.model.Patient patient)) return false;
        return patient.getName().stream().anyMatch(name -> {
            List<String> parts = new ArrayList<>(name.getGiven().stream().map(Object::toString).toList());
            if (name.hasFamily()) parts.add(name.getFamily());
            if (name.hasText()) parts.addAll(Arrays.asList(name.getText().split("\\s+")));
            return parts.stream().anyMatch(part -> normalize(part).startsWith(prefix));
        });
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath().substring("/fhir/".length());
            requests.add(method + " " + exchange.getRequestURI());
            String[] parts = path.split("/");
            if (method.equals("POST") && parts.length == 1) {
                create(exchange, parts[0]);
            } else if (method.equals("POST") && parts.length == 2 && parts[1].equals("_search")) {
                formSearch(exchange, parts[0]);
            } else if (method.equals("PUT") && parts.length == 2) {
                update(exchange, parts[0], parts[1]);
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
        String condition = exchange.getRequestHeaders().getFirst("If-None-Exist");
        String id;
        synchronized (this) {
            if (condition != null) {
                conditions.add(condition);
                String[] identifier = identifierCondition(condition);
                if (identifier == null) {
                    outcome(exchange, 400, OperationOutcome.IssueType.NOTSUPPORTED);
                    return;
                }
                List<String> matches = resources.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(type + "/")
                                && hasIdentifier(entry.getValue(), identifier[0], identifier[1]))
                        .map(Map.Entry::getKey).toList();
                if (matches.size() > 1) {
                    outcome(exchange, 412, OperationOutcome.IssueType.DUPLICATE);
                    return;
                }
                if (matches.size() == 1) {
                    // As HAPI: nothing is created; 200 OK names the existing resource.
                    exchange.getResponseHeaders().set("Location", baseUrl + "/" + matches.getFirst() + "/_history/1");
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
            }
            id = "local-" + ids.incrementAndGet();
            stamp(resource, id);
            resources.put(type + "/" + id, resource);
        }
        exchange.getResponseHeaders().set("Location", baseUrl + "/" + type + "/" + id + "/_history/1");
        exchange.sendResponseHeaders(201, -1);
    }

    /**
     * {@code PUT [base]/{Type}/{id}} as HAPI does it for an existing resource: the body replaces it with the next
     * version (200 with the stored resource). With {@code If-Match} (`W/"n"` or `"n"`) a different current version
     * gives 409 (HAPI's version conflict). An unknown ID gives 404 (no update-as-create).
     */
    private void update(HttpExchange exchange, String type, String id) throws IOException {
        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        ifMatches.add(ifMatch == null ? "" : ifMatch);
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (updatesUnavailable) {
            outcome(exchange, 503, OperationOutcome.IssueType.TRANSIENT);
            return;
        }
        if (updateDelayMillis > 0) {
            try {
                Thread.sleep(updateDelayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Resource resource = (Resource) CONTEXT.newJsonParser().parseResource(new String(body, StandardCharsets.UTF_8));
        if (!type.equals(resource.fhirType()) || !id.equals(resource.getIdElement().getIdPart())) {
            outcome(exchange, 400, OperationOutcome.IssueType.INVALID);
            return;
        }
        Resource stored;
        synchronized (this) {
            Resource current = resources.get(type + "/" + id);
            if (current == null) {
                outcome(exchange, 404, OperationOutcome.IssueType.NOTFOUND);
                return;
            }
            String currentVersion = current.getMeta().getVersionId();
            if (ifMatch != null && !ifMatch.replaceFirst("^W/", "").replace("\"", "").equals(currentVersion)) {
                outcome(exchange, 409, OperationOutcome.IssueType.CONFLICT);
                return;
            }
            String next = String.valueOf(Integer.parseInt(currentVersion) + 1);
            resource.setId(id);
            resource.getMeta().setVersionId(next).setLastUpdated(new Date());
            resources.put(type + "/" + id, resource);
            stored = resource.copy();
        }
        String version = stored.getMeta().getVersionId();
        exchange.getResponseHeaders().set("Location", baseUrl + "/" + type + "/" + id + "/_history/" + version);
        exchange.getResponseHeaders().set("ETag", "W/\"" + version + "\"");
        send(exchange, 200, stored);
    }

    /** {@code [Type?]identifier=system|value} as {system, value}; any other condition is unsupported (null). */
    private static String[] identifierCondition(String condition) {
        String query = condition.contains("?") ? condition.substring(condition.indexOf('?') + 1) : condition;
        if (!query.startsWith("identifier=") || query.contains("&")) return null;
        String token = URLDecoder.decode(query.substring("identifier=".length()), StandardCharsets.UTF_8);
        int bar = token.indexOf('|');
        if (bar <= 0 || bar == token.length() - 1) return null;
        return new String[] {token.substring(0, bar), token.substring(bar + 1)};
    }

    private static boolean hasIdentifier(Resource resource, String system, String value) {
        var property = resource.getNamedProperty("identifier");
        return property != null && property.getValues().stream().anyMatch(item -> item instanceof Identifier identifier
                && system.equals(identifier.getSystem()) && value.equals(identifier.getValue()));
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
