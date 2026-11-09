package com.medinexus.medicore.patient.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The patient update body may contain only the gender. */
class UpdatePatientRequestTest {

    private static Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("gender", "MALE");
        return body;
    }

    @Test
    void readsTheGender() {
        assertThat(UpdatePatientRequest.from(body())).isEqualTo(new UpdatePatientRequest(CreatePatientRequest.Gender.MALE));
        Map<String, Object> missing = new HashMap<>();
        missing.put("gender", null);
        assertThat(UpdatePatientRequest.from(missing)).isEqualTo(new UpdatePatientRequest(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"givenName", "familyName", "personnummer", "birthDate", "id", "fhirPatientId",
            "identifier", "identifiers", "email", "phoneNumber", "telecom", "userId", "role", "version", "names",
            "given", "family", "active"})
    void namesIdentityContactAndUnknownFieldsAreRejected(String field) {
        Map<String, Object> body = body();
        body.put(field, "200512241971");
        assertThatThrownBy(() -> UpdatePatientRequest.from(body))
                .isInstanceOfSatisfying(IllegalArgumentException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain("200512241971"));
    }

    @Test
    void nonTextValuesUnknownGendersAndMissingBodiesAreRejected() {
        for (Object value : new Object[] {42, true, java.util.List.of("MALE"), Map.of("x", "y")}) {
            Map<String, Object> body = body();
            body.put("gender", value);
            assertThatThrownBy(() -> UpdatePatientRequest.from(body)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String gender : new String[] {"male", "M", "", "NONBINARY"}) {
            Map<String, Object> body = body();
            body.put("gender", gender);
            assertThatThrownBy(() -> UpdatePatientRequest.from(body)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> UpdatePatientRequest.from(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
