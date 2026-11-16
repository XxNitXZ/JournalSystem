package com.medinexus.medicore.patient.dto;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.medinexus.medicore.patient.validation.Personnummer;

/**
 * A validated patient search: either an exact personnummer or one to five name parts that must all match.
 *
 * <p>A query of twelve digits, optionally written {@code YYYYMMDD-NNNN}, is a personnummer search and must be a
 * valid personnummer. Anything else is a name search: parts are separated by whitespace and contain only
 * letters, combining marks, hyphens and apostrophes; together they must have at least two letters. Error messages
 * never contain the query.
 */
public record PatientSearchCriteria(String personnummer, List<String> nameParts) {

    private static final Pattern PERSONNUMMER = Pattern.compile("\\d{8}-?\\d{4}");
    private static final Pattern NAME_PART = Pattern.compile("[\\p{L}\\p{M}'’-]+");
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_NAME_PARTS = 5;

    public PatientSearchCriteria {
        nameParts = nameParts == null ? List.of() : List.copyOf(nameParts);
    }

    public boolean isPersonnummerSearch() {
        return personnummer != null;
    }

    /** @throws IllegalArgumentException for an empty, too long or malformed query */
    public static PatientSearchCriteria parse(String query) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("A search for a name or a personnummer is required");
        }
        if (PERSONNUMMER.matcher(trimmed).matches()) {
            String digits = trimmed.replace("-", "");
            if (!Personnummer.isValid(digits)) {
                throw new IllegalArgumentException("A valid Swedish personnummer is required");
            }
            return new PatientSearchCriteria(digits, List.of());
        }
        List<String> parts = Arrays.stream(trimmed.split("\\s+")).toList();
        long letters = trimmed.codePoints().filter(Character::isLetter).count();
        if (parts.size() > MAX_NAME_PARTS || letters < 2
                || !parts.stream().allMatch(part -> NAME_PART.matcher(part).matches())) {
            throw new IllegalArgumentException("Search for at least two letters of a name or a complete personnummer");
        }
        return new PatientSearchCriteria(null, parts);
    }
}
