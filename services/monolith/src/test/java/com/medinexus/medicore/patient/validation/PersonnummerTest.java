package com.medinexus.medicore.patient.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MediCore's personnummer rules: exactly 12 digits whose first eight are a real date; no Luhn check digit and no
 * allowlist. Fictional identities only.
 */
class PersonnummerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @ParameterizedTest
    @CsvSource({
            // Fictional numbers, most with a wrong Luhn check digit: the last four digits are not validated.
            "200412201971, 2004-12-20",
            "199912319999, 1999-12-31",
            "200001010000, 2000-01-01",
            "182003151237, 1820-03-15",
            // Leap day, and numbers that also happen to have a correct check digit.
            "200002292381, 2000-02-29",
            "199001012385, 1990-01-01",
            "196407133252, 1964-07-13"})
    void acceptsTwelveDigitsWithARealDateAndReturnsTheBirthDate(String personnummer, LocalDate birthDate) {
        assertThat(Personnummer.isValid(personnummer)).isTrue();
        assertThat(Personnummer.birthDate(personnummer)).isEqualTo(birthDate);
        assertThat(Personnummer.normalize(personnummer, TODAY)).isEqualTo(personnummer);
    }

    @Test
    void theLuhnCheckDigitIsNeverValidated() {
        // Every possible last digit of the same number is accepted, so the check digit plays no part.
        for (int digit = 0; digit <= 9; digit++) {
            assertThat(Personnummer.isValid("19900101238" + digit)).isTrue();
        }
        assertThat(luhnIsCorrect("200412201971")).isFalse();
        assertThat(Personnummer.isValid("200412201971")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 30 February, month 13 and day 40, 29 Feb in a non-leap year, coordination number (day + 60), day 00.
            "202602301234", "200413401234", "199902291234", "199001611234", "199001001234",
            // Length and characters: 5, 11 and 13 digits, letters, plus sign, inner and outer spaces, non-ASCII digits.
            "12345", "19900101238", "1990010123850", "ABCDEFGHIJKL", "19900101238X", "19900101+2385",
            "1990 0101 2385", " 199001012385", "١٩٩٠٠١٠١٢٣٨٥", "",
            // The hyphenated form is accepted only by normalize, never as a stored value.
            "19900101-2385"})
    void rejectsWrongLengthsCharactersAndImpossibleDates(String personnummer) {
        assertThat(Personnummer.isValid(personnummer)).isFalse();
        assertThatThrownBy(() -> Personnummer.birthDate(personnummer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A valid Swedish personnummer is required");
    }

    @Test
    void normalizeAcceptsTheHyphenatedFormAndRejectsFutureBirthDates() {
        assertThat(Personnummer.normalize("20041220-1971", TODAY)).isEqualTo("200412201971");
        assertThat(Personnummer.normalize("  200412201971 ", TODAY)).isEqualTo("200412201971");
        // Born today is allowed; tomorrow and 2035 are in the future.
        assertThat(Personnummer.normalize("202611091234", TODAY)).isEqualTo("202611091234");
        assertThat(Personnummer.normalize("202611101234", TODAY)).isNull();
        assertThat(Personnummer.normalize("203512201234", TODAY)).isNull();
        // Nothing is truncated or rearranged.
        for (String invalid : new String[] {"2004122019710", "0412201971", "2004-12-20-1971", "200412-201971",
                "20041220--1971", "202602301234", "12345", "ABCDEFGHIJKL", "", "   "}) {
            assertThat(Personnummer.normalize(invalid, TODAY)).as(invalid).isNull();
        }
        assertThat(Personnummer.normalize(null, TODAY)).isNull();
    }

    @Test
    void rejectsNull() {
        assertThat(Personnummer.isValid(null)).isFalse();
        assertThatThrownBy(() -> Personnummer.birthDate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesTheFhirIdentifierSystemForSwedishPersonnummer() {
        assertThat(Personnummer.SYSTEM).isEqualTo("http://electronichealth.se/identifier/personnummer");
    }

    private static boolean luhnIsCorrect(String digits) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int value = (digits.charAt(i + 2) - '0') * (i % 2 == 0 ? 2 : 1);
            sum += value > 9 ? value - 9 : value;
        }
        return (10 - sum % 10) % 10 == digits.charAt(11) - '0';
    }
}
