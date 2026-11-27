package com.medinexus.medicore.patient.dto;

/**
 * Body of {@code POST /api/patients/search}: a name (first, last or full) or an exact personnummer. Sent in the
 * body, never in the URL, so that personnummer do not appear in URLs, browser history or access logs.
 */
public record PatientSearchRequest(String query) {
}
