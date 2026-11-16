package com.medinexus.medicore.fhir.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * FHIR R4 {@code DocumentReference} access for clinical notes (US-05.01). Only two interactions are used, both
 * declared for DocumentReference by the KTH server's CapabilityStatement: {@code create} and {@code search-type}
 * with the {@code subject} parameter. Attachment URLs are never fetched: notes are read from inline data only.
 * Every request runs through {@link FhirRequestExecutor} (safe logging, {@link FhirServerException}).
 */
@Component
public class FhirDocumentReferenceClient {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_RESULTS = 1_000;

    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    private final FhirReferences references;

    public FhirDocumentReferenceClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor) {
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
        this.references = new FhirReferences(fhirClient);
    }

    /**
     * {@code POST [base]/DocumentReference}; returns the server-assigned logical ID. The response must confirm the
     * creation of a DocumentReference with a valid ID. A failed response may follow a committed write (for example
     * after a timeout), so callers must not retry blindly.
     */
    public String createDocumentReference(DocumentReference resource) {
        if (resource == null || resource.hasId()) {
            throw new IllegalArgumentException("A new DocumentReference without an ID is required");
        }
        return requestExecutor.execute("document-reference-create", () -> {
            var outcome = fhirClient.create().resource(resource).execute();
            var id = outcome == null ? null : outcome.getId();
            if (outcome == null || !Boolean.TRUE.equals(outcome.getCreated()) || id == null
                    || !"DocumentReference".equals(id.getResourceType()) || id.getIdPart() == null
                    || !id.getIdPart().matches(LOGICAL_ID)) {
                throw new DataFormatException("DocumentReference creation response did not confirm a valid ID");
            }
            return id.getIdPart();
        });
    }

    /**
     * Searches {@code subject=Patient/{patientId}}, following at most 10 pages / 1,000 results, and returns no
     * partial results. Every entry must be a DocumentReference with a valid logical ID, a search match, and have
     * exactly this Patient as subject (relative, or absolute on the configured base, without version); otherwise the
     * whole search fails with {@code INVALID_RESPONSE}, so that another Patient's note is never returned. Next links
     * must stay within the configured FHIR base and may not repeat.
     */
    public List<DocumentReference> findByPatientId(String patientId) {
        if (patientId == null || !patientId.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        return requestExecutor.execute("document-reference-search", () -> search(patientId));
    }

    /** See {@link FhirReferences#refersTo}. */
    public boolean refersTo(Reference reference, String type, String id) {
        return references.refersTo(reference, type, id);
    }

    /** See {@link FhirReferences#logicalId}. */
    public String logicalId(Reference reference, String type) {
        return references.logicalId(reference, type);
    }

    private List<DocumentReference> search(String patientId) {
        String base = fhirClient.getServerBase();
        URI current = URI.create(base + "/DocumentReference");
        Set<URI> visited = new HashSet<>();
        visited.add(current);
        Set<String> ids = new HashSet<>();
        List<DocumentReference> results = new ArrayList<>();
        Bundle bundle = fhirClient.search().forResource(DocumentReference.class)
                .where(DocumentReference.SUBJECT.hasId("Patient/" + patientId)).count(PAGE_SIZE)
                .returnBundle(Bundle.class).execute();
        for (int page = 1; ; page++) {
            if (bundle == null || bundle.getType() != Bundle.BundleType.SEARCHSET) throw invalid();
            for (var entry : bundle.getEntry()) {
                if (entry.hasSearch() && entry.getSearch().hasMode()
                        && entry.getSearch().getMode() != Bundle.SearchEntryMode.MATCH) {
                    throw invalid();
                }
                if (!(entry.getResource() instanceof DocumentReference note) || !note.hasIdElement()
                        || note.getIdElement().getIdPart() == null
                        || !note.getIdElement().getIdPart().matches(LOGICAL_ID)
                        || !ids.add(note.getIdElement().getIdPart())
                        || !refersTo(note.getSubject(), "Patient", patientId)) {
                    throw invalid();
                }
                if (results.size() == MAX_RESULTS) throw invalid();
                results.add(note);
            }
            var links = bundle.getLink().stream().filter(link -> "next".equals(link.getRelation())).toList();
            if (links.isEmpty()) return List.copyOf(results);
            if (page == MAX_PAGES || links.size() != 1 || !links.getFirst().hasUrl()) throw invalid();
            URI next = nextUri(current, links.getFirst().getUrl(), URI.create(base));
            if (!visited.add(next)) throw invalid();
            current = next;
            bundle = fhirClient.loadPage().byUrl(next.toString()).andReturnBundle(Bundle.class).execute();
        }
    }

    private URI nextUri(URI current, String link, URI base) {
        try {
            URI supplied = URI.create(link);
            // URI.resolve treats query-only links specially; retain the current endpoint path.
            URI next = link.startsWith("?")
                    ? URI.create(current.toString().split("\\?", 2)[0] + link) : current.resolve(supplied);
            String path = next.getPath();
            if (!base.getScheme().equalsIgnoreCase(next.getScheme())
                    || !base.getHost().equalsIgnoreCase(next.getHost()) || base.getPort() != next.getPort()
                    || next.getUserInfo() != null || next.getFragment() != null
                    || path == null || !path.equals(base.getPath()) && !path.startsWith(base.getPath() + "/")
                    || !next.normalize().equals(next) || next.getRawPath().contains("%") || path.contains("\\")) {
                throw invalid();
            }
            return next;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static DataFormatException invalid() {
        return new DataFormatException("Invalid or unsafe DocumentReference search response");
    }
}
