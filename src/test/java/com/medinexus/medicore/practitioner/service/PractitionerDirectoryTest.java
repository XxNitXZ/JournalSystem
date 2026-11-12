package com.medinexus.medicore.practitioner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.practitioner.dto.PractitionerOption;

/** Practitioners that can be chosen for an Encounter: those linked to MediCore clinicians. Fictional data. */
class PractitionerDirectoryTest {

    private final UserRepository users = mock(UserRepository.class);
    private final FhirPractitionerClient practitioners = mock(FhirPractitionerClient.class);
    private final PractitionerDirectory directory = new PractitionerDirectory(users, practitioners);

    private UserDto signedIn(Role role, String practitionerId) {
        User account = new User();
        ReflectionTestUtils.setField(account, "id", 7L);
        account.setRole(role);
        account.setFhirPractitionerId(practitionerId);
        when(users.findById(7L)).thenReturn(Optional.of(account));
        return new UserDto(7L, "x@example.test", role);
    }

    private static Practitioner practitioner(String id, String given, String family) {
        Practitioner practitioner = new Practitioner();
        practitioner.setId(id);
        practitioner.addName().setUse(HumanName.NameUse.OFFICIAL).setFamily(family).addGiven(given).addPrefix("Dr");
        return practitioner;
    }

    @Test
    void listsLinkedPractitionersByNameWithTheOwnFirstAndSkipsMissingOnes() {
        UserDto doctor = signedIn(Role.DOCTOR, "p-own");
        when(users.findClinicianPractitionerIds()).thenReturn(List.of("p-a", "p-gone", "p-own", "p-b"));
        when(practitioners.readPractitioner("p-a")).thenReturn(practitioner("p-a", "Sara", "Ahmed"));
        when(practitioners.readPractitioner("p-b")).thenReturn(practitioner("p-b", "Bo", "Berg"));
        when(practitioners.readPractitioner("p-own")).thenReturn(practitioner("p-own", "Ali", "Khan"));
        when(practitioners.readPractitioner("p-gone")).thenThrow(new FhirServerException("practitioner-read",
                FhirServerException.Type.CLIENT_ERROR, 404, List.of(), null));

        assertThat(directory.available(doctor)).containsExactly(
                new PractitionerOption("p-own", "Dr Ali Khan", true),
                new PractitionerOption("p-b", "Dr Bo Berg", false),
                new PractitionerOption("p-a", "Dr Sara Ahmed", false));
    }

    @Test
    void anOutageIsReportedAndPatientsCannotList() {
        UserDto staff = signedIn(Role.STAFF, null);
        when(users.findClinicianPractitionerIds()).thenReturn(List.of("p-a"));
        FhirServerException down = new FhirServerException("practitioner-read", FhirServerException.Type.UNAVAILABLE,
                null, List.of(), "x");
        when(practitioners.readPractitioner("p-a")).thenThrow(down);
        assertThatThrownBy(() -> directory.available(staff)).isSameAs(down);

        UserDto patient = signedIn(Role.PATIENT, null);
        org.mockito.Mockito.reset(practitioners);
        assertThatThrownBy(() -> directory.available(patient)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> directory.available(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(practitioners);
    }

    @Test
    void onlyLinkedPractitionersAreSelectable() {
        when(users.isClinicianPractitioner("p-a")).thenReturn(true);
        assertThat(directory.isSelectable("p-a")).isTrue();
        assertThat(directory.isSelectable("p-foreign")).isFalse();
        assertThat(directory.isSelectable(null)).isFalse();
    }

    @Test
    void displayNamesUseTheOfficialNameOrTheText() {
        assertThat(PractitionerDirectory.displayName(practitioner("p", "Ali", "Khan"))).isEqualTo("Dr Ali Khan");
        Practitioner textOnly = new Practitioner();
        textOnly.addName().setText("Ali Khan, MD");
        assertThat(PractitionerDirectory.displayName(textOnly)).isEqualTo("Ali Khan, MD");
        assertThat(PractitionerDirectory.displayName(new Practitioner())).isNull();
        assertThat(PractitionerDirectory.displayName(null)).isNull();
    }
}
