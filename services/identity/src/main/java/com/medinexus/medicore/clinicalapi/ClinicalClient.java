package com.medinexus.medicore.clinicalapi;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.medinexus.medicore.common.http.ServiceClients;

/**
 * Identity's only way to learn about FHIR Patients and Practitioners (D2, D6): the Clinical internal API C1–C4
 * (docs/architecture.md §12), called with a service token and the timeouts of {@link ServiceClients}.
 *
 * <p>A "not found" answer (404) is part of the result ({@code Optional.empty()} / {@code false}). Every other failure
 * is thrown: {@code ResourceAccessException} when Clinical is unreachable or too slow, {@code RestClientResponseException}
 * when it answers with an error. Callers that can carry on without Clinical catch {@code RestClientException};
 * otherwise {@code GlobalExceptionHandler} turns them into 503 or 502.
 */
@Component
public class ClinicalClient {

    /** C1: a registered record with the account's personnummer, and Clinical's verdict on its personnummer. */
    public record RegisteredRecord(String id, String personnummerStatus, String personnummer) {

        /** True only for exactly one valid personnummer equal to this canonical value (PatientPersonnummer.matches). */
        public boolean matches(String canonicalPersonnummer) {
            return "VALID".equals(personnummerStatus) && canonicalPersonnummer != null
                    && canonicalPersonnummer.equals(personnummer);
        }
    }

    record RegisteredRecords(List<RegisteredRecord> records) {
    }

    /** C2: a patient record as a doctor reviews it. */
    public record PatientRecord(String id, List<String> givenNames, String familyName, String personnummerStatus,
            String personnummer, String gender, String birthDate) {

        public PatientRecord {
            givenNames = givenNames == null ? List.of() : List.copyOf(givenNames);
        }

        public boolean matches(String canonicalPersonnummer) {
            return "VALID".equals(personnummerStatus) && canonicalPersonnummer != null
                    && canonicalPersonnummer.equals(personnummer);
        }
    }

    private final RestClient clinical;

    public ClinicalClient(ServiceClients clients, @Value("${medinexus.clinical.url}") String clinicalUrl) {
        this.clinical = clients.forService(clinicalUrl);
    }

    /** C1. */
    public List<RegisteredRecord> registeredRecords(String personnummer) {
        RegisteredRecords result = clinical.get()
                .uri(uri -> uri.path("/internal/patients/registered").queryParam("personnummer", personnummer).build())
                .retrieve().body(RegisteredRecords.class);
        return result == null || result.records() == null ? List.of() : result.records();
    }

    /** C2; empty if Clinical doesn't know the patient or FHIR has no such Patient. */
    public Optional<PatientRecord> patientRecord(String fhirPatientId) {
        try {
            return Optional.ofNullable(clinical.get().uri("/internal/patients/{id}", fhirPatientId).retrieve()
                    .body(PatientRecord.class));
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        }
    }

    /** C3; false if FHIR has no such Patient. */
    public boolean registerPatient(String fhirPatientId) {
        return exists(() -> clinical.post().uri("/internal/registry/{id}", fhirPatientId).retrieve().toBodilessEntity()
                .getStatusCode());
    }

    /** C4; false if FHIR has no such Practitioner. */
    public boolean practitionerExists(String fhirPractitionerId) {
        return exists(() -> clinical.get().uri("/internal/practitioners/{id}", fhirPractitionerId).retrieve()
                .toBodilessEntity().getStatusCode());
    }

    /** True for a 2xx answer, false for 404; anything else is thrown by the RestClient. */
    private static boolean exists(Supplier<HttpStatusCode> call) {
        try {
            return call.get().is2xxSuccessful();
        } catch (HttpClientErrorException.NotFound missing) {
            return false;
        }
    }
}
