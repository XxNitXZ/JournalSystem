package com.medinexus.medicore.fhir.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.medinexus.medicore.medicalrecord.dto.EncounterSearchResult;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.parser.DataFormatException;

import com.medinexus.medicore.fhir.mapper.EncounterMapper;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;

@Component
public class FhirEncounterClient {

    private static final Logger log = LoggerFactory.getLogger(FhirEncounterClient.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_RESULTS = 1_000;

    private final EncounterMapper mapper;
    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirEncounterClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor, EncounterMapper mapper) {
        this.mapper = mapper;
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Encounter/[id]. A missing resource remains a translated HTTP 404. */
    public Encounter readEncounter(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Encounter ID is required");
        }
        return requestExecutor.execute("encounter-read", () -> fhirClient.read()
                .resource(Encounter.class).withId(id).execute());
    }

    /** Internal POST [base]/Encounter. A failed response may follow a committed write; do not retry blindly. */
    public String createEncounter(CreateEncounterRequest request) {
        Encounter resource = mapper.toResource(request);
        return requestExecutor.execute("encounter-create", () -> {
            var outcome = fhirClient.create().resource(resource).execute();
            var id = outcome == null ? null : outcome.getId();
            if (outcome == null || !Boolean.TRUE.equals(outcome.getCreated()) || id == null
                    || !"Encounter".equals(id.getResourceType()) || id.getIdPart() == null
                    || !id.getIdPart().matches("[A-Za-z0-9.-]{1,64}")) {
                throw new DataFormatException("Encounter creation response did not confirm a valid assigned ID");
            }
            return id.getIdPart();
        });
    }

    /**
     * Searches subject=Patient/{patientId}, following at most 10 pages / 1,000 examined entries.
     * Reports missing-class exclusions explicitly. All other failures return no results. Subjects must be exact relative or same-base absolute
     * versionless Patient references; identifier-only and versioned references fail closed.
     * Server next links must remain within the configured FHIR base.
     */
    public EncounterSearchResult findByPatientId(String patientId) {
        if (patientId == null || !patientId.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        return requestExecutor.execute("encounter-search", () -> search(patientId));
    }

    private EncounterSearchResult search(String patientId) {
        String reference = "Patient/" + patientId;
        String base = fhirClient.getServerBase();
        URI current = URI.create(base + "/Encounter");
        Set<URI> visited = new HashSet<>();
        visited.add(current);
        List<EncounterDto> results = new ArrayList<>();
        int examined = 0;
        int excluded = 0;
        Bundle bundle = fhirClient.search().forResource(Encounter.class)
                .where(Encounter.SUBJECT.hasId(reference)).count(PAGE_SIZE)
                .returnBundle(Bundle.class).execute();
        for (int page = 1; ; page++) {
            if (bundle == null || bundle.getType() != Bundle.BundleType.SEARCHSET) throw invalid();
            for (var entry : bundle.getEntry()) {
                if (!(entry.getResource() instanceof Encounter encounter)
                        || entry.hasSearch() && entry.getSearch().hasMode()
                            && entry.getSearch().getMode() != Bundle.SearchEntryMode.MATCH
                        || !encounter.hasId() || !encounter.getIdElement().getIdPart().matches("[A-Za-z0-9.-]{1,64}")
                        || !encounter.hasSubject() || !encounter.getSubject().hasReference()) throw invalid();
                String subject = encounter.getSubject().getReference();
                if (!reference.equals(subject) && !(base + "/" + reference).equals(subject)) throw invalid();
                if (encounter.getSubject().hasType() && !"Patient".equals(encounter.getSubject().getType())
                        && !"http://hl7.org/fhir/StructureDefinition/Patient".equals(encounter.getSubject().getType())) throw invalid();
                if (++examined > MAX_RESULTS) throw invalid();
                try {
                    if (!encounter.hasClass_()) {
                        mapper.validateMissingClassCandidate(encounter);
                        excluded++;
                    } else {
                        results.add(mapper.toDto(encounter));
                    }
                } catch (IllegalArgumentException exception) {
                    throw invalid();
                }
            }
            var links = bundle.getLink().stream().filter(link -> "next".equals(link.getRelation())).toList();
            if (links.isEmpty()) {
                if (excluded > 0) log.warn("FHIR search incomplete: operation=encounter-search reason=MISSING_CLASS excludedCount={}", excluded);
                return new EncounterSearchResult(results, excluded == 0, excluded);
            }
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
        return new DataFormatException("Invalid or unsafe Encounter search response");
    }
}
