package com.medinexus.medicore.fhir.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.parser.DataFormatException;

import com.medinexus.medicore.fhir.mapper.ObservationMapper;
import com.medinexus.medicore.medicalrecord.dto.CreateObservationRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;

@Component
public class FhirObservationClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_RESULTS = 1_000;

    private final ObservationMapper mapper;
    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirObservationClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor, ObservationMapper mapper) {
        this.mapper = mapper;
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Observation/[id]. A missing resource remains a translated HTTP 404. */
    public Observation readObservation(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Observation ID is required");
        }
        return requestExecutor.execute("observation-read", () -> fhirClient.read()
                .resource(Observation.class).withId(id).execute());
    }

    /** Internal POST [base]/Observation. A failed response may follow a committed write; do not retry blindly. */
    public String createObservation(CreateObservationRequest request) {
        Observation resource = mapper.toResource(request);
        return requestExecutor.execute("observation-create", () -> {
            var outcome = fhirClient.create().resource(resource).execute();
            var id = outcome == null ? null : outcome.getId();
            if (outcome == null || !Boolean.TRUE.equals(outcome.getCreated()) || id == null
                    || !"Observation".equals(id.getResourceType()) || id.getIdPart() == null
                    || !id.getIdPart().matches("[A-Za-z0-9.-]{1,64}")) {
                throw new DataFormatException("Observation creation response did not confirm a valid assigned ID");
            }
            return id.getIdPart();
        });
    }

    /**
     * Searches subject=Patient/{patientId}, following at most 10 pages / 1,000 results.
     * Returns no partial results. Subjects must be exact relative or same-base absolute
     * versionless Patient references; identifier-only and versioned references fail closed.
     * Server next links must remain within the configured FHIR base.
     */
    public List<ObservationDto> findByPatientId(String patientId) {
        if (patientId == null || !patientId.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        return requestExecutor.execute("observation-search", () -> search(patientId));
    }

    private List<ObservationDto> search(String patientId) {
        String reference = "Patient/" + patientId;
        String base = fhirClient.getServerBase();
        URI current = URI.create(base + "/Observation");
        Set<URI> visited = new HashSet<>();
        visited.add(current);
        List<ObservationDto> results = new ArrayList<>();
        Bundle bundle = fhirClient.search().forResource(Observation.class)
                .where(Observation.SUBJECT.hasId(reference)).count(PAGE_SIZE)
                .returnBundle(Bundle.class).execute();
        for (int page = 1; ; page++) {
            if (bundle == null || bundle.getType() != Bundle.BundleType.SEARCHSET) throw invalid();
            for (var entry : bundle.getEntry()) {
                if (!(entry.getResource() instanceof Observation observation)
                        || entry.hasSearch() && entry.getSearch().hasMode()
                            && entry.getSearch().getMode() != Bundle.SearchEntryMode.MATCH
                        || !observation.hasId() || !observation.getIdElement().getIdPart().matches("[A-Za-z0-9.-]{1,64}")
                        || !observation.hasSubject() || !observation.getSubject().hasReference()) throw invalid();
                String subject = observation.getSubject().getReference();
                if (!reference.equals(subject) && !(base + "/" + reference).equals(subject)) throw invalid();
                if (observation.getSubject().hasType() && !"Patient".equals(observation.getSubject().getType())
                        && !"http://hl7.org/fhir/StructureDefinition/Patient".equals(observation.getSubject().getType())) throw invalid();
                if (results.size() == MAX_RESULTS) throw invalid();
                try {
                    results.add(mapper.toDto(observation));
                } catch (IllegalArgumentException exception) {
                    throw invalid();
                }
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

    private DataFormatException invalid() {
        return new DataFormatException("Invalid or unsafe Observation search response");
    }
}
