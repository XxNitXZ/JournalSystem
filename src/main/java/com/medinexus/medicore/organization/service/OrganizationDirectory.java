package com.medinexus.medicore.organization.service;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.fhir.client.FhirOrganizationClient;
import com.medinexus.medicore.organization.dto.OrganizationOption;

@Service
public class OrganizationDirectory {
    private final UserRepository users;
    private final FhirOrganizationClient organizations;

    public OrganizationDirectory(UserRepository users, FhirOrganizationClient organizations) {
        this.users = users;
        this.organizations = organizations;
    }

    public List<OrganizationOption> available(UserDto principal) {
        if (principal == null || principal.id() == null || principal.id() <= 0) throw denied();
        var account = users.findById(principal.id()).orElseThrow(OrganizationDirectory::denied);
        if (account.getRole() != Role.DOCTOR && account.getRole() != Role.STAFF) throw denied();
        return organizations.findActiveOrganizations().stream()
                .map(organization -> new OrganizationOption(organization.id(), organization.name())).toList();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Access denied"); }
}
