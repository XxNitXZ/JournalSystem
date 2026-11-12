package com.medinexus.medicore.fhir.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.PreferReturnEnum;
import ca.uhn.fhir.rest.api.SearchStyleEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IQuery;

import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.patient.dto.CreatePatientRequest;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.medinexus.medicore.patient.validation.Personnummer;

@Component
public class FhirPatientClient {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";
    static final int ID_BATCH = 50;
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 10;
    public static final int MAX_RESULTS = 1_000;

    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;
    // PatientMapper is stateless. It is not injected so that contexts that only read Patients (such as the
    // FHIR identity provisioning command) keep working without registering the mapper.
    private final PatientMapper mapper = new PatientMapper();

    public FhirPatientClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor) {
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /**
     * Searches only among {@code allowedIds}: by exact personnummer or by name parts (each part must match the
     * beginning of a name, as HAPI's {@code name} parameter does; all parts must match).
     *
     * <p>Every request is {@code POST [base]/Patient/_search} with a form body, so neither names nor personnummer
     * appear in URLs. The IDs are sent in batches of {@value #ID_BATCH} as {@code _id=a,b,c}, so other Patients on
     * the shared server are never requested. Up to {@value #MAX_PAGES} pages of {@value #PAGE_SIZE} are followed
     * per batch; next links must stay within the configured FHIR base. Every returned Patient must be one of the
     * requested IDs and appear once, otherwise the whole search fails ({@code INVALID_RESPONSE}). For a
     * personnummer search only Patients with exactly that personnummer are returned.
     *
     * @throws TooManyPatientsException if more than {@value #MAX_RESULTS} Patients match
     */
    public List<Patient> searchPatients(PatientSearchCriteria criteria, List<String> allowedIds) {
        if (criteria == null || allowedIds == null
                || allowedIds.stream().anyMatch(id -> id == null || !id.matches(LOGICAL_ID))) {
            throw new IllegalArgumentException("Valid search criteria and logical FHIR Patient IDs are required");
        }
        List<String> ids = List.copyOf(new TreeSet<>(allowedIds));
        if (ids.isEmpty()) return List.of();
        return requestExecutor.execute("patient-search", () -> {
            List<Patient> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int from = 0; from < ids.size(); from += ID_BATCH) {
                searchBatch(criteria, ids.subList(from, Math.min(from + ID_BATCH, ids.size())), results, seen);
            }
            return List.copyOf(results);
        });
    }

    /** Page size of a discovery search (import of existing Patients). */
    public static final int DISCOVERY_PAGE_SIZE = 20;
    /** At most this many pages (FHIR requests) per discovery search. */
    public static final int DISCOVERY_MAX_PAGES = 5;
    /** At most this many Patients per discovery search; more are reported as truncated. */
    public static final int DISCOVERY_MAX_RESULTS = 100;

    /** Result of a discovery search: the Patients found, and whether more exist than were fetched. */
    public record Discovery(List<Patient> patients, boolean truncated) {
        public Discovery {
            patients = List.copyOf(patients);
        }
    }

    /**
     * Discovery search on the <strong>whole</strong> configured FHIR server (not restricted to MediCore's registry),
     * by exact personnummer or name parts, to find existing Patients to import. Read-only: {@code POST
     * [base]/Patient/_search} with a form body, so names and personnummer never appear in URLs.
     *
     * <p>Bounded: {@value #DISCOVERY_PAGE_SIZE} per page, at most {@value #DISCOVERY_MAX_PAGES} pages and
     * {@value #DISCOVERY_MAX_RESULTS} Patients; beyond that the result is marked truncated instead of failing. Every
     * entry must be a Patient with a valid logical ID, appear once and be a search match, and next links must stay on
     * the configured FHIR base; otherwise the whole search fails ({@code INVALID_RESPONSE}). For a personnummer search
     * only Patients with exactly that personnummer are returned.
     */
    public Discovery discoverPatients(PatientSearchCriteria criteria) {
        if (criteria == null) throw new IllegalArgumentException("Valid search criteria are required");
        return requestExecutor.execute("patient-discovery", () -> {
            IQuery<IBaseBundle> query = fhirClient.search().forResource(Patient.class);
            if (criteria.isPersonnummerSearch()) {
                query = query.where(personnummerCriterion(criteria.personnummer()));
            } else {
                for (String part : criteria.nameParts()) query = query.where(Patient.NAME.matches().value(part));
            }
            String base = fhirClient.getServerBase();
            URI current = URI.create(base + "/Patient/_search");
            Set<URI> visited = new HashSet<>();
            Set<String> seen = new HashSet<>();
            List<Patient> results = new ArrayList<>();
            Bundle bundle = query.count(DISCOVERY_PAGE_SIZE).usingStyle(SearchStyleEnum.POST)
                    .returnBundle(Bundle.class).execute();
            for (int page = 1; ; page++) {
                if (bundle == null || bundle.getType() != Bundle.BundleType.SEARCHSET) throw invalidSearch();
                for (var entry : bundle.getEntry()) {
                    if (!(entry.getResource() instanceof Patient patient)
                            || entry.hasSearch() && entry.getSearch().hasMode()
                                && entry.getSearch().getMode() != Bundle.SearchEntryMode.MATCH) {
                        throw invalidSearch();
                    }
                    String id = patient.getIdElement().getIdPart();
                    if (id == null || !id.matches(LOGICAL_ID) || !seen.add(id)) throw invalidSearch();
                    if (criteria.isPersonnummerSearch() && !hasPersonnummer(patient, criteria.personnummer())) continue;
                    if (results.size() == DISCOVERY_MAX_RESULTS) return new Discovery(results, true);
                    results.add(patient);
                }
                var links = bundle.getLink().stream().filter(link -> "next".equals(link.getRelation())).toList();
                if (links.isEmpty()) return new Discovery(results, false);
                if (links.size() != 1 || !links.getFirst().hasUrl()) throw invalidSearch();
                if (page == DISCOVERY_MAX_PAGES) return new Discovery(results, true);
                URI next = nextUri(current, links.getFirst().getUrl(), URI.create(base));
                if (!visited.add(next)) throw invalidSearch();
                current = next;
                bundle = fhirClient.loadPage().byUrl(next.toString()).andReturnBundle(Bundle.class).execute();
            }
        });
    }

    private void searchBatch(PatientSearchCriteria criteria, List<String> batch, List<Patient> results, Set<String> seen) {
        IQuery<IBaseBundle> query = fhirClient.search().forResource(Patient.class)
                .where(IAnyResource.RES_ID.exactly().codes(batch));
        if (criteria.isPersonnummerSearch()) {
            query = query.and(personnummerCriterion(criteria.personnummer()));
        } else {
            for (String part : criteria.nameParts()) {
                query = query.and(Patient.NAME.matches().value(part));
            }
        }
        String base = fhirClient.getServerBase();
        URI current = URI.create(base + "/Patient/_search");
        Set<URI> visited = new HashSet<>();
        Bundle bundle = query.count(PAGE_SIZE).usingStyle(SearchStyleEnum.POST).returnBundle(Bundle.class).execute();
        for (int page = 1; ; page++) {
            if (bundle == null || bundle.getType() != Bundle.BundleType.SEARCHSET) throw invalidSearch();
            for (var entry : bundle.getEntry()) {
                if (!(entry.getResource() instanceof Patient patient)
                        || entry.hasSearch() && entry.getSearch().hasMode()
                            && entry.getSearch().getMode() != Bundle.SearchEntryMode.MATCH) {
                    throw invalidSearch();
                }
                String id = patient.getIdElement().getIdPart();
                // Never accept a Patient that was not asked for, or the same Patient twice.
                if (id == null || !batch.contains(id) || !seen.add(id)) throw invalidSearch();
                if (criteria.isPersonnummerSearch() && !hasPersonnummer(patient, criteria.personnummer())) continue;
                if (results.size() == MAX_RESULTS) throw new TooManyPatientsException();
                results.add(patient);
            }
            var links = bundle.getLink().stream().filter(link -> "next".equals(link.getRelation())).toList();
            if (links.isEmpty()) return;
            if (page == MAX_PAGES || links.size() != 1 || !links.getFirst().hasUrl()) throw invalidSearch();
            URI next = nextUri(current, links.getFirst().getUrl(), URI.create(base));
            if (!visited.add(next)) throw invalidSearch();
            current = next;
            bundle = fhirClient.loadPage().byUrl(next.toString()).andReturnBundle(Bundle.class).execute();
        }
    }

    /**
     * {@code identifier=system|YYYYMMDDXXXX,system|YYYYMMDD-XXXX} (FHIR token search, OR): a Patient may store the
     * personnummer in either form (MediCore writes 12 digits, the KTH server uses a hyphen). Same system only.
     */
    private static ca.uhn.fhir.rest.gclient.ICriterion<?> personnummerCriterion(String personnummer) {
        return Patient.IDENTIFIER.exactly().systemAndValues(Personnummer.SYSTEM,
                Personnummer.identifierValues(personnummer));
    }

    /** True if a personnummer identifier (MediCore's system only) has this canonical value in a supported form. */
    private static boolean hasPersonnummer(Patient patient, String personnummer) {
        return patient.getIdentifier().stream().anyMatch(identifier ->
                Personnummer.SYSTEM.equals(identifier.getSystem())
                        && personnummer.equals(Personnummer.canonical(identifier.getValue())));
    }

    /** A next link resolved against the current page; it must stay on the configured FHIR base. */
    private static URI nextUri(URI current, String link, URI base) {
        try {
            URI next = link.startsWith("?")
                    ? URI.create(current.toString().split("\\?", 2)[0] + link) : current.resolve(URI.create(link));
            String path = next.getPath();
            if (!base.getScheme().equalsIgnoreCase(next.getScheme())
                    || !base.getHost().equalsIgnoreCase(next.getHost()) || base.getPort() != next.getPort()
                    || next.getUserInfo() != null || next.getFragment() != null
                    || path == null || !path.equals(base.getPath()) && !path.startsWith(base.getPath() + "/")
                    || !next.normalize().equals(next) || next.getRawPath().contains("%") || path.contains("\\")) {
                throw invalidSearch();
            }
            return next;
        } catch (IllegalArgumentException exception) {
            throw invalidSearch();
        }
    }

    private static DataFormatException invalidSearch() {
        return new DataFormatException("Invalid or unsafe Patient search response");
    }

    /** Reads GET [base]/Patient/[id]. A missing resource remains a translated HTTP 404. */
    public Patient readPatient(String id) {
        if (id == null || !id.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Patient ID is required");
        }
        return requestExecutor.execute("patient-read", () -> fhirClient.read()
                .resource(Patient.class).withId(id).execute());
    }

    /**
     * Version-aware update {@code PUT [base]/Patient/{id}} with {@code If-Match: W/"expectedVersion"} (US-04.04).
     * The given Patient (as read and then changed by the caller) is sent whole, so every element the caller did not
     * change is written back unchanged. The server rejects the update if the Patient changed in between (HAPI: 409
     * or 412, reported as {@code CLIENT_ERROR}); nothing is overwritten then. Never creates a Patient: a response
     * saying that a resource was created, or naming another resource, is an invalid response.
     *
     * @return the Patient as stored, with its new version
     */
    public Patient updatePatient(Patient patient, String expectedVersion) {
        String id = patient == null ? null : patient.getIdElement().getIdPart();
        if (id == null || !id.matches(LOGICAL_ID) || expectedVersion == null || !expectedVersion.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A Patient with a valid logical ID and version is required");
        }
        Patient body = patient.copy();
        IdType target = new IdType("Patient", id);
        body.setIdElement(target.copy());
        return requestExecutor.execute("patient-update", () -> {
            MethodOutcome outcome = fhirClient.update().resource(body).withId(target)
                    .withAdditionalHeader("If-Match", "W/\"" + expectedVersion + "\"")
                    .prefer(PreferReturnEnum.REPRESENTATION).execute();
            if (outcome == null || Boolean.TRUE.equals(outcome.getCreated())) throw invalidUpdate();
            IIdType written = outcome.getId();
            if (written != null && !written.isEmpty() && (!"Patient".equals(written.getResourceType())
                    || !id.equals(written.getIdPart()))) {
                throw invalidUpdate();
            }
            Patient stored;
            if (outcome.getResource() instanceof Patient returned) {
                if (!id.equals(returned.getIdElement().getIdPart())) throw invalidUpdate();
                stored = returned;
            } else {
                // No representation returned: the Patient as sent, with the version the server reported.
                stored = body;
                if (written != null && written.hasVersionIdPart()) {
                    stored.setIdElement(target.withVersion(written.getVersionIdPart()));
                    stored.getMeta().setVersionId(written.getVersionIdPart());
                }
            }
            return stored;
        });
    }

    /** Translated and logged by {@link FhirRequestExecutor} as {@code INVALID_RESPONSE}, like a create. */
    private static DataFormatException invalidUpdate() {
        return new DataFormatException("Patient update response did not confirm the same Patient");
    }

    /**
     * Conditional create {@code POST [base]/Patient} with
     * {@code If-None-Exist: identifier=http://electronichealth.se/identifier/personnummer|{personnummer}};
     * returns the assigned logical ID.
     *
     * <p>The request is validated before any HTTP request. If the server finds exactly one Patient with the same
     * personnummer it creates nothing and answers 200; several matches give 412. Both are reported as
     * {@link DuplicatePatientException}. A failed response may follow a committed write; there are no automatic
     * retries. Repeating the same registration later is safe: it then reports the duplicate.
     */
    public String createPatient(CreatePatientRequest request) {
        Patient resource = mapper.toResource(request);
        String personnummer = resource.getIdentifierFirstRep().getValue();
        try {
            return requestExecutor.execute("patient-create", () -> {
                var outcome = fhirClient.create().resource(resource).conditional()
                        .where(Patient.IDENTIFIER.exactly().systemAndIdentifier(Personnummer.SYSTEM, personnummer))
                        .execute();
                if (outcome == null) throw invalidCreate();
                var id = outcome.getId();
                boolean hasId = id != null && !id.isEmpty();
                if (hasId && (!"Patient".equals(id.getResourceType())
                        || id.getIdPart() == null || !id.getIdPart().matches(LOGICAL_ID))) {
                    throw invalidCreate();
                }
                if (!Boolean.TRUE.equals(outcome.getCreated())) {
                    // 200 OK instead of 201: the server matched an existing Patient and created nothing.
                    // The existing Patient's ID (if returned) is deliberately not passed on.
                    throw new DuplicatePatientException();
                }
                if (!hasId) throw invalidCreate();
                return id.getIdPart();
            });
        } catch (FhirServerException failure) {
            if (failure.type() == FhirServerException.Type.CLIENT_ERROR && failure.httpStatus().orElse(0) == 412) {
                // Several existing Patients match the personnummer.
                throw new DuplicatePatientException();
            }
            throw failure;
        }
    }

    private static DataFormatException invalidCreate() {
        return new DataFormatException("Patient creation response did not confirm a valid assigned ID");
    }
}
