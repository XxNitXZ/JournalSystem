package com.medinexus.medicore.medicalrecord.dto;

import java.util.Map;
import java.util.Set;

import com.medinexus.medicore.medicalrecord.service.DiagnosisRequestException;

/**
 * Body of {@code POST /api/patients/{patientId}/conditions} (US-05.02). The Patient comes from the path and the
 * recorder from the signed-in clinician's linked Practitioner, never from the body: any other property (such as
 * {@code patientId}, {@code subject}, {@code recorder} or {@code practitionerId}) or a non-text value is rejected.
 * Values are validated by {@code DiagnosisService}.
 *
 * @param diagnosis          the diagnosis as text (required), stored as {@code Condition.code.text}
 * @param code               optional code in {@code codeSystem}
 * @param codeSystem         {@code SNOMED_CT} or {@code ICD_10}; required with a code
 * @param clinicalStatus     an R4 clinical status name ({@code ACTIVE} when absent)
 * @param verificationStatus {@code CONFIRMED} (default), {@code PROVISIONAL}, {@code DIFFERENTIAL} or
 *                           {@code UNCONFIRMED}
 * @param onsetDate          optional onset date {@code YYYY-MM-DD}
 * @param encounterId        optional visit (an Encounter of this Patient)
 */
public record NewDiagnosisRequest(String diagnosis, String code, String codeSystem, String clinicalStatus,
        String verificationStatus, String onsetDate, String encounterId) {

    private static final Set<String> FIELDS = Set.of("diagnosis", "code", "codeSystem", "clinicalStatus",
            "verificationStatus", "onsetDate", "encounterId");

    public static NewDiagnosisRequest from(Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet())) throw new DiagnosisRequestException("INVALID_REQUEST");
        return new NewDiagnosisRequest(text(body, "diagnosis"), text(body, "code"), text(body, "codeSystem"),
                text(body, "clinicalStatus"), text(body, "verificationStatus"), text(body, "onsetDate"),
                text(body, "encounterId"));
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value != null && !(value instanceof String)) throw new DiagnosisRequestException("INVALID_REQUEST");
        String text = (String) value;
        return text == null || text.isBlank() ? null : text.strip();
    }
}
