package com.medinexus.medicore.fhir.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.parser.DataFormatException;

import com.medinexus.medicore.fhir.mapper.ConditionMapper;
import com.medinexus.medicore.medicalrecord.dto.ConditionDto;

@Component
public class FhirConditionClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_RESULTS = 1_000;

    private final ConditionMapper mapper;
    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirConditionClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor, ConditionMapper mapper) {
        this.mapper = mapper;
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Condition/[id]. A missing resource remains a translated HTTP 404. */
    public Condition readCondition(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Condition ID is required");
        }
        return requestExecutor.execute("condition-read", () -> fhirClient.read()
                .resource(Condition.class).withId(id).execute());
    }

    /**
     * Searches subject=Patient/{patientId}, following at most 10 pages / 1,000 results.
     * Returns no partial results. Subjects must be exact relative or same-base absolute
     * versionless Patient references; identifier-only and versioned references fail closed.
     * Server next links must remain within the configured FHIR base.
     */
    public List<ConditionDto> findByPatientId(String patientId) {
        if (patientId == null || !patientId.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        return requestExecutor.execute("condition-search", () -> search(patientId));
    }

    private List<ConditionDto> search(String patientId) {
        String reference = "Patient/" + patientId;
        String base = fhirClient.getServerBase();
        URI current = URI.create(base + "/Condition");
        Set<URI> visited = new HashSet<>();
        visited.add(current);
        List<ConditionDto> results = new ArrayList<>();
        Bundle bundle = fhirClient.search().forResource(Condition.class)
                .where(Condition.SUBJECT.hasId(reference)).count(PAGE_SIZE)
                .returnBundle(Bundle.class).execute();
        for (int page = 1; ; page++) {
            if (bundle == null || bundle.getType() != Bundle.BundleType.SEARCHSET) throw invalid();
            for (var entry : bundle.getEntry()) {
                if (!(entry.getResource() instanceof Condition condition)
                        || entry.hasSearch() && entry.getSearch().hasMode()
                            && entry.getSearch().getMode() != Bundle.SearchEntryMode.MATCH
                        || !condition.hasId() || !condition.getIdElement().getIdPart().matches("[A-Za-z0-9.-]{1,64}")
                        || !condition.hasSubject() || !condition.getSubject().hasReference()) throw invalid();
                String subject = condition.getSubject().getReference();
                if (!reference.equals(subject) && !(base + "/" + reference).equals(subject)) throw invalid();
                if (condition.getSubject().hasType() && !"Patient".equals(condition.getSubject().getType())
                        && !"http://hl7.org/fhir/StructureDefinition/Patient".equals(condition.getSubject().getType())) throw invalid();
                if (results.size() == MAX_RESULTS) throw invalid();
                try {
                    results.add(mapper.toDto(condition));
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
        return new DataFormatException("Invalid or unsafe Condition search response");
    }
}
