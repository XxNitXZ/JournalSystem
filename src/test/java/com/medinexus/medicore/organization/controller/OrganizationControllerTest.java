package com.medinexus.medicore.organization.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.medinexus.medicore.auth.dto.UserDto;
import com.medinexus.medicore.auth.entity.Role;
import com.medinexus.medicore.auth.entity.User;
import com.medinexus.medicore.auth.repository.UserRepository;
import com.medinexus.medicore.auth.service.AuthenticationService;
import com.medinexus.medicore.config.SecurityConfig;
import com.medinexus.medicore.fhir.client.FhirOrganizationClient;
import com.medinexus.medicore.fhir.client.FhirServerException;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.medinexus.medicore.organization.service.OrganizationDirectory;

@WebMvcTest(OrganizationController.class)
@Import({SecurityConfig.class, OrganizationDirectory.class})
class OrganizationControllerTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationDirectory directory;
    @MockitoBean UserRepository users;
    @MockitoBean FhirOrganizationClient organizations;
    @MockitoBean AuthenticationService authenticationService;

    private RequestPostProcessor as(Role role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserDto(7L, "synthetic@example.test", role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    private void persisted(Role role) {
        User account = new User(); account.setRole(role);
        when(users.findById(7L)).thenReturn(Optional.of(account));
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"DOCTOR", "STAFF"})
    void cliniciansReceiveOnlyIdAndName(Role role) throws Exception {
        persisted(role);
        var dto = new OrganizationDto("synthetic-1", "version-secret", List.of(), true, List.of(), "Synthetic Provider",
                List.of("hidden-alias"), List.of(), List.of(), null);
        when(organizations.findActiveOrganizations()).thenReturn(List.of(dto));
        mvc.perform(get("/api/organizations").with(as(role))).andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"synthetic-1\",\"name\":\"Synthetic Provider\"}]",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }
    @Test void emptyDirectoryIsAnEmptyArray() throws Exception {
        persisted(Role.DOCTOR);
        when(organizations.findActiveOrganizations()).thenReturn(List.of());
        mvc.perform(get("/api/organizations").with(as(Role.DOCTOR))).andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
    @Test void anonymousPatientsStaleAndDeletedAccountsCannotSearch() throws Exception {
        mvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/organizations").with(as(Role.PATIENT))).andExpect(status().isForbidden());
        persisted(Role.PATIENT);
        mvc.perform(get("/api/organizations").with(as(Role.DOCTOR))).andExpect(status().isForbidden());
        when(users.findById(7L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/organizations").with(as(Role.STAFF))).andExpect(status().isForbidden());
        persisted(null);
        mvc.perform(get("/api/organizations").with(as(Role.DOCTOR))).andExpect(status().isForbidden());
        verifyNoInteractions(organizations);
    }
    @Test void directServiceCallsRejectInvalidPrincipals() {
        for (UserDto principal : new UserDto[] {null, new UserDto(null, "synthetic", Role.DOCTOR),
                new UserDto(0L, "synthetic", Role.STAFF)}) {
            assertThatThrownBy(() -> directory.available(principal))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        verifyNoInteractions(users, organizations);
    }
    @Test void arbitraryReadAndWriteEndpointsRemainDenied() throws Exception {
        mvc.perform(post("/api/organizations").with(as(Role.DOCTOR)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/organizations/arbitrary").with(as(Role.DOCTOR))).andExpect(status().isForbidden());
        verifyNoInteractions(organizations);
    }
    @ParameterizedTest @EnumSource(FhirServerException.Type.class)
    void upstreamErrorsHaveSafeEmptyResponses(FhirServerException.Type type) throws Exception {
        persisted(Role.DOCTOR);
        when(organizations.findActiveOrganizations()).thenThrow(new FhirServerException("organization-search", type, null, List.of(), null));
        int expected = type == FhirServerException.Type.TIMEOUT || type == FhirServerException.Type.UNAVAILABLE ? 503 : 502;
        mvc.perform(get("/api/organizations").with(as(Role.DOCTOR))).andExpect(status().is(expected))
                .andExpect(content().string(""));
    }
}
