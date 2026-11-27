package com.medinexus.medicore.medicalrecord.dto;

/**
 * The code systems a clinician may give a diagnosis code in (US-05.02), with their official FHIR system URIs. Codes
 * are checked for structure only; MediCore has no terminology server, so it cannot check that a code exists. A
 * diagnosis without a code is stored as text only and no code is ever invented.
 */
public enum DiagnosisCodeSystem {

    /** SNOMED CT concept ID: 6 to 18 digits, concept partition ({@code 00} or {@code 10}), Verhoeff check digit. */
    SNOMED_CT("http://snomed.info/sct", "SNOMED CT"),
    /** ICD-10 (WHO): a letter, two digits and an optional subdivision, e.g. {@code J45} or {@code E11.9}. */
    ICD_10("http://hl7.org/fhir/sid/icd-10", "ICD-10");

    private final String system;
    private final String label;

    DiagnosisCodeSystem(String system, String label) {
        this.system = system;
        this.label = label;
    }

    public String system() {
        return system;
    }

    public String label() {
        return label;
    }

    /** The code in its stored form (ICD-10 in upper case), or null if it is not structurally valid. */
    public String normalize(String code) {
        if (code == null) return null;
        String value = code.strip();
        return switch (this) {
            case SNOMED_CT -> isSnomedConceptId(value) ? value : null;
            case ICD_10 -> {
                String upper = value.toUpperCase(java.util.Locale.ROOT);
                yield upper.matches("[A-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?") ? upper : null;
            }
        };
    }

    /** The code system with this FHIR system URI, or null. */
    public static DiagnosisCodeSystem fromSystem(String system) {
        for (DiagnosisCodeSystem candidate : values()) {
            if (candidate.system.equals(system)) return candidate;
        }
        return null;
    }

    static boolean isSnomedConceptId(String value) {
        if (!value.matches("[1-9][0-9]{5,17}")) return false;
        String partition = value.substring(value.length() - 3, value.length() - 1);
        return (partition.equals("00") || partition.equals("10")) && verhoeffValid(value);
    }

    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    /** Verhoeff check over all digits (the last one is the check digit), as used by SNOMED CT identifiers. */
    private static boolean verhoeffValid(String digits) {
        int check = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            check = D[check][P[i % 8][digit]];
        }
        return check == 0;
    }
}
