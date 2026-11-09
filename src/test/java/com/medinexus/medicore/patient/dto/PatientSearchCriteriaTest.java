package com.medinexus.medicore.patient.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic data only: personnummer from Skatteverket's published test personnummer or deliberately invalid. */
class PatientSearchCriteriaTest {

    @Test
    void twelveDigitsWithOrWithoutHyphenAreAnExactPersonnummerSearch() {
        for (String query : new String[] {"199001012385", " 19900101-2385 "}) {
            PatientSearchCriteria criteria = PatientSearchCriteria.parse(query);
            assertThat(criteria.isPersonnummerSearch()).isTrue();
            assertThat(criteria.personnummer()).isEqualTo("199001012385");
            assertThat(criteria.nameParts()).isEmpty();
        }
    }

    @Test
    void aWrongLuhnCheckDigitIsStillAnExactPersonnummerSearch() {
        // The check digit is not validated; the search value is the exact identifier.
        for (String query : new String[] {"199001012386", "19900101-2386"}) {
            assertThat(PatientSearchCriteria.parse(query).personnummer()).isEqualTo("199001012386");
        }
    }

    @Test
    void namesAreSplitIntoPartsThatMustAllMatch() {
        assertThat(PatientSearchCriteria.parse("  Anna   Testsson ").nameParts()).containsExactly("Anna", "Testsson");
        assertThat(PatientSearchCriteria.parse("Anna-Karin").nameParts()).containsExactly("Anna-Karin");
        assertThat(PatientSearchCriteria.parse("O'Neil Öberg").nameParts()).containsExactly("O'Neil", "Öberg");
        assertThat(PatientSearchCriteria.parse("Åa").nameParts()).containsExactly("Åa");
        assertThat(PatientSearchCriteria.parse("Anna").isPersonnummerSearch()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a", "-", "Anna1", "Anna, Testsson", "Anna <b>", "a b c d e f",
            // Invalid date or length: not a personnummer, and digits are not names.
            "199002301234", "19900230-1234", "199013019808", "9001012385", "1990010123850"})
    void invalidQueriesAreRejectedWithoutEchoingThem(String query) {
        assertThatThrownBy(() -> PatientSearchCriteria.parse(query))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain("Anna", "1990"));
    }

    @Test
    void nullAndTooLongQueriesAreRejected() {
        assertThatThrownBy(() -> PatientSearchCriteria.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PatientSearchCriteria.parse("a".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertThat(PatientSearchCriteria.parse("a".repeat(100)).nameParts()).hasSize(1);
    }
}
