package com.medinexus.medicore.practitioner.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirPractitionerClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.practitioner.dto.PractitionerOption;

/**
 * The Practitioners that belong to MediCore: those linked to DOCTOR and STAFF accounts ({@code
 * users.fhir_practitioner_id}, set only by the trusted operator command). No Practitioner is created, and no account
 * ID is ever taken for a Practitioner ID. Like the patient registry, this keeps MediCore away from other users'
 * resources on the shared FHIR server: only these Practitioners can be chosen for an Encounter.
 */
@Service
public class PractitionerDirectory {

    /** Upper bound for the selection list (one FHIR read per Practitioner). */
    static final int MAX_OPTIONS = 100;

    private final UserRepository users;
    private final FhirPractitionerClient practitioners;

    public PractitionerDirectory(UserRepository users, FhirPractitionerClient practitioners) {
        this.users = users;
        this.practitioners = practitioners;
    }

    /**
     * The selectable Practitioners with their FHIR names, the signed-in clinician's own first, then by name.
     * Practitioners that no longer exist in FHIR are left out.
     *
     * @throws AccessDeniedException unless the account is currently a DOCTOR or STAFF
     * @throws FhirServerException   if the FHIR server cannot be read
     */
    public List<PractitionerOption> available(UserDto principal) {
        User account = requireClinician(principal);
        List<PractitionerOption> options = new ArrayList<>();
        for (String id : users.findClinicianPractitionerIds().stream().limit(MAX_OPTIONS).toList()) {
            Practitioner practitioner;
            try {
                practitioner = practitioners.readPractitioner(id);
            } catch (FhirServerException failure) {
                if (isMissing(failure)) continue;
                throw failure;
            }
            if (practitioner == null || !id.equals(practitioner.getIdElement().getIdPart())) continue;
            String name = displayName(practitioner);
            options.add(new PractitionerOption(id, name == null ? "Name not recorded" : name,
                    id.equals(account.getFhirPractitionerId())));
        }
        options.sort(Comparator.comparing(PractitionerOption::own).reversed()
                .thenComparing(PractitionerOption::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(options);
    }

    /** True if the Practitioner is linked to a DOCTOR or STAFF account, i.e. may be chosen. */
    public boolean isSelectable(String practitionerId) {
        return practitionerId != null && users.isClinicianPractitioner(practitionerId);
    }

    /** "Dr Ali Khan": the official name (otherwise the first), prefix, given names and family name; or null. */
    public static String displayName(Practitioner practitioner) {
        if (practitioner == null || !practitioner.hasName()) return null;
        HumanName name = practitioner.getName().stream().filter(candidate -> candidate.getUse() == HumanName.NameUse.OFFICIAL)
                .findFirst().orElse(practitioner.getNameFirstRep());
        List<String> parts = new ArrayList<>();
        name.getPrefix().stream().map(StringType::getValue).forEach(parts::add);
        name.getGiven().stream().map(StringType::getValue).forEach(parts::add);
        parts.add(name.getFamily());
        String joined = parts.stream().filter(part -> part != null && !part.isBlank()).map(String::strip)
                .collect(Collectors.joining(" "));
        if (!joined.isEmpty()) return joined;
        return name.hasText() && !name.getText().isBlank() ? name.getText().strip() : null;
    }

    private User requireClinician(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        User account = users.findById(principal.id()).orElseThrow(PractitionerDirectory::denied);
        if (account.getRole() != Role.DOCTOR && account.getRole() != Role.STAFF) throw denied();
        return account;
    }

    static boolean isMissing(FhirServerException failure) {
        int status = failure.httpStatus().orElse(0);
        return failure.type() == FhirServerException.Type.CLIENT_ERROR && (status == 404 || status == 410);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }
}
