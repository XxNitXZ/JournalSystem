package com.medinexus.medicore.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.medinexus.medicore.common.exception.ApiError;
import com.medinexus.medicore.fhir.client.FhirPatientClient;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.fhir.mapper.PatientMapper;
import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientSearchCriteria;
import com.medinexus.medicore.patient.registry.PatientRegistry;
import com.medinexus.medicore.patient.validation.PatientPersonnummer;
import com.medinexus.medicore.patient.validation.Personnummer;

import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;

/**
 * The Clinical endpoints Identity calls (docs/architecture.md §12, C1–C4), served by the monolith until the Clinical
 * service exists (NIT-330). Only service tokens reach them ({@code /internal/**} requires ROLE_SERVICE).
 * Clinical (US-02.02) implements the same contract; then this class is removed with the monolith.
 */
@RestController
public class ClinicalInternalController {

    private static final String FHIR_ID = "[A-Za-z0-9.-]{1,64}";
    private static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");

    /** C1: one registered record with this personnummer, and Clinical's verdict on its personnummer. */
    public record RegisteredRecord(String id, String personnummerStatus, String personnummer) {
    }

    public record RegisteredRecords(List<RegisteredRecord> records) {
    }

    /** C2: what a doctor reviews before approving journal access. */
    public record PatientRecord(String id, List<String> givenNames, String familyName, String personnummerStatus,
            String personnummer, String gender, String birthDate) {
    }

    private final PatientRegistry registry;
    private final FhirPatientClient patients;
    private final FhirPractitionerClient practitioners;
    private final PatientMapper mapper;
    private final Clock clock;

    @Autowired
    public ClinicalInternalController(PatientRegistry registry, FhirPatientClient patients,
            FhirPractitionerClient practitioners, PatientMapper mapper) {
        this(registry, patients, practitioners, mapper, Clock.systemUTC());
    }

    ClinicalInternalController(PatientRegistry registry, FhirPatientClient patients,
            FhirPractitionerClient practitioners, PatientMapper mapper, Clock clock) {
        this.registry = registry;
        this.patients = patients;
        this.practitioners = practitioners;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** C1: an exact personnummer search restricted to MediCore's registered patients. */
    @GetMapping("/internal/patients/registered")
    public RegisteredRecords registeredPatients(@RequestParam String personnummer) {
        if (personnummer == null || !personnummer.matches("[0-9]{12}")) throw new IllegalArgumentException();
        List<String> registered = registry.registeredIds();
        if (registered.isEmpty()) return new RegisteredRecords(List.of());
        LocalDate today = LocalDate.now(clock.withZone(SWEDEN));
        List<RegisteredRecord> records = patients.searchPatients(PatientSearchCriteria.parse(personnummer), registered)
                .stream()
                .map(patient -> {
                    PatientPersonnummer verdict = PatientPersonnummer.of(patient, today);
                    return new RegisteredRecord(patient.getIdElement().getIdPart(), verdict.status().name(),
                            verdict.status() == PatientPersonnummer.Status.VALID ? verdict.value() : null);
                })
                .toList();
        return new RegisteredRecords(records);
    }

    /** C2: the record of a known patient, as a doctor reviews it. */
    @GetMapping("/internal/patients/{id}")
    public ResponseEntity<?> patient(@PathVariable String id) {
        if (!id.matches(FHIR_ID)) throw new IllegalArgumentException();
        if (!registry.isKnown(id)) return ApiError.response(HttpStatus.NOT_FOUND);
        Patient patient = patients.readPatient(id);
        if (patient == null) return ApiError.response(HttpStatus.NOT_FOUND);
        if (!id.equals(patient.getIdElement().getIdPart())) return ApiError.response(HttpStatus.BAD_GATEWAY);
        return ResponseEntity.ok(record(id, mapper.toDto(patient),
                PatientPersonnummer.of(patient, LocalDate.now(clock.withZone(SWEDEN)))));
    }

    /** C3: an operator links an account to an existing Patient; the Patient joins the registry. */
    @PostMapping("/internal/registry/{id}")
    public ResponseEntity<?> register(@PathVariable String id) {
        if (!id.matches(FHIR_ID)) throw new IllegalArgumentException();
        Patient patient = patients.readPatient(id);
        if (patient == null) return ApiError.response(HttpStatus.NOT_FOUND);
        if (!id.equals(patient.getIdElement().getIdPart())) return ApiError.response(HttpStatus.BAD_GATEWAY);
        registry.recordImport(id, null);
        return ResponseEntity.noContent().build();
    }

    /** C4: whether a Practitioner exists. */
    @GetMapping("/internal/practitioners/{id}")
    public ResponseEntity<?> practitioner(@PathVariable String id) {
        if (!id.matches(FHIR_ID)) throw new IllegalArgumentException();
        Practitioner practitioner = practitioners.readPractitioner(id);
        if (practitioner == null) return ApiError.response(HttpStatus.NOT_FOUND);
        if (!id.equals(practitioner.getIdElement().getIdPart())) return ApiError.response(HttpStatus.BAD_GATEWAY);
        return ResponseEntity.noContent().build();
    }

    /** Same choice of name and personnummer as Lab 1's JournalAccessService.record. */
    private static PatientRecord record(String id, PatientDto patient, PatientPersonnummer canonical) {
        var name = patient.names().stream().filter(candidate -> "official".equals(candidate.use())).findFirst()
                .or(() -> patient.names().stream().findFirst());
        String personnummer = canonical.status() == PatientPersonnummer.Status.VALID ? canonical.value()
                : patient.identifiers().stream().filter(identifier -> Personnummer.SYSTEM.equals(identifier.system()))
                        .map(PatientDto.PatientIdentifier::value).findFirst().orElse(null);
        return new PatientRecord(id, name.map(PatientDto.PatientName::given).orElse(List.of()),
                name.map(PatientDto.PatientName::family).orElse(null), canonical.status().name(), personnummer,
                patient.gender(), patient.birthDate());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid() {
        return ApiError.response(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(FhirServerException.class)
    public ResponseEntity<ApiError> fhirFailure(FhirServerException failure) {
        int status = failure.httpStatus().orElse(0);
        if (failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410)) {
            return ApiError.response(HttpStatus.NOT_FOUND);
        }
        return ApiError.response(failure.type() == FhirServerException.Type.TIMEOUT
                || failure.type() == FhirServerException.Type.UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.BAD_GATEWAY);
    }
}
