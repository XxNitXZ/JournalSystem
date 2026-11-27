package com.medinexus.medicore.patient.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.medinexus.medicore.patient.validation.PatientPersonnummer.Status;

/** Equivalent personnummer formats (YYYYMMDDXXXX, YYYYMMDD-XXXX) in FHIR Patients. Fictional numbers only. */
class PatientPersonnummerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static Identifier personnummer(String value) {
        return new Identifier().setSystem(Personnummer.SYSTEM).setValue(value);
    }

    private static PatientPersonnummer of(Identifier... identifiers) {
        return PatientPersonnummer.of(List.of(identifiers), TODAY);
    }

    @Test
    void theHyphenatedFormIsTheSamePersonnummerAndTwelveDigitsStayUnchanged() {
        assertThat(Personnummer.canonical("19500417-0677")).isEqualTo("195004170677");
        assertThat(Personnummer.canonical(" 19500417-0677 ")).isEqualTo("195004170677");
        assertThat(Personnummer.canonical("195004170677")).isEqualTo("195004170677");
        assertThat(Personnummer.normalize("19500417-0677", TODAY)).isEqualTo("195004170677");
        assertThat(Personnummer.identifierValues("195004170677")).containsExactly("195004170677", "19500417-0677");
        assertThatThrownBy(() -> Personnummer.identifierValues("19500417-0677")).isInstanceOf(IllegalArgumentException.class);
        assertThat(of(personnummer("19500417-0677"))).isEqualTo(new PatientPersonnummer(Status.VALID, "195004170677"));
        assertThat(of(personnummer("195004170677"))).isEqualTo(new PatientPersonnummer(Status.VALID, "195004170677"));
    }

    @ParameterizedTest
    // Other separator positions or characters, short or long forms, an impossible date (Luhn is never checked).
    @ValueSource(strings = {"1950041-70677", "195004-170677", "19500417 0677", "19500417+0677", "19500417--0677",
            "500417-0677", "5004170677", "1950-04-17-0677", "19500417-06777", "19501317-0677", "19500230-0677",
            "1950O417-0677", ""})
    void otherFormsAndInvalidDatesAreNotPersonnummer(String value) {
        assertThat(Personnummer.canonical(value)).isNull();
        assertThat(of(personnummer(value)).status()).isIn(Status.INVALID, Status.MISSING);
    }

    @Test
    void aFutureBirthDateIsRejectedAndTheChecksumIsNotChecked() {
        assertThat(of(personnummer("20261113-0000")).status()).isEqualTo(Status.INVALID);
        assertThat(of(personnummer("20261112-0000")).status()).isEqualTo(Status.VALID);
        // Any last four digits (no Luhn check), as for accounts.
        assertThat(of(personnummer("19500417-0000")).value()).isEqualTo("195004170000");
    }

    @Test
    void onlyMediCoresIdentifierSystemCounts() {
        assertThat(of(new Identifier().setSystem("http://hospital.smarthealthit.org").setValue("195004170677")).status())
                .isEqualTo(Status.MISSING);
        assertThat(of(new Identifier().setValue("19500417-0677")).status()).isEqualTo(Status.MISSING);
        assertThat(of(new Identifier().setSystem("http://electronichealth.se/identifier/personnummer/").setValue(
                "195004170677")).status()).isEqualTo(Status.MISSING);
        // The KTH server's Synthea and MRN identifiers next to the personnummer do not interfere.
        assertThat(of(new Identifier().setSystem("https://github.com/synthetichealth/synthea").setValue("195004170677"),
                personnummer("19500417-0677")).status()).isEqualTo(Status.VALID);
    }

    @Test
    void conflictingPersonnummerAreAmbiguousAndNeverMatch() {
        PatientPersonnummer twoPeople = of(personnummer("19500417-0677"), personnummer("198001010000"));
        assertThat(twoPeople).isEqualTo(new PatientPersonnummer(Status.AMBIGUOUS, null));
        assertThat(twoPeople.matches("195004170677")).isFalse();
        assertThat(of(personnummer("19500417-0677"), personnummer("19501317-0677")).status()).isEqualTo(Status.AMBIGUOUS);
        // The same person in both forms is not a conflict.
        assertThat(of(personnummer("19500417-0677"), personnummer("195004170677")).matches("195004170677")).isTrue();
    }

    @Test
    void matchingIsExactOnTheCanonicalValue() {
        PatientPersonnummer valid = of(personnummer("19500417-0677"));
        assertThat(valid.matches("195004170677")).isTrue();
        assertThat(valid.matches("19500417-0677")).isFalse();
        assertThat(valid.matches("195004170678")).isFalse();
        assertThat(valid.matches(null)).isFalse();
        assertThat(of(personnummer("19501317-0677"))).isEqualTo(new PatientPersonnummer(Status.INVALID, "19501317-0677"));
        assertThat(PatientPersonnummer.of((Patient) null, TODAY).status()).isEqualTo(Status.MISSING);
        assertThat(PatientPersonnummer.of(new Patient(), TODAY).status()).isEqualTo(Status.MISSING);
    }
}
