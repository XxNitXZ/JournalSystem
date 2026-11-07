package com.medinexus.medicore.fhir.client;

import java.util.Objects;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Organization;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;

import com.medinexus.medicore.fhir.mapper.OrganizationMapper;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;

@Component
public class FhirOrganizationClient {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    private final OrganizationMapper mapper;
    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirOrganizationClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor,
            OrganizationMapper mapper) {
        this.mapper = mapper;
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Organization/[id]. A missing resource remains a translated HTTP 404. */
    public Organization readOrganization(String id) {
        requireId(id);
        return requestExecutor.execute("organization-read", () -> fhirClient.read()
                .resource(Organization.class).withId(id).execute());
    }

    /** Internal POST [base]/Organization. A failed response may follow a committed write; do not retry blindly. */
    public String createOrganization(OrganizationWriteRequest request) {
        Organization resource = mapper.toResource(request);
        return requestExecutor.execute("organization-create", () -> {
            var outcome = fhirClient.create().resource(resource).execute();
            var id = outcome == null ? null : outcome.getId();
            if (outcome == null || !Boolean.TRUE.equals(outcome.getCreated()) || id == null
                    || !"Organization".equals(id.getResourceType()) || id.getIdPart() == null
                    || !id.getIdPart().matches(LOGICAL_ID)) {
                throw new DataFormatException("Organization creation response did not confirm a valid assigned ID");
            }
            return id.getIdPart();
        });
    }

    /**
     * Read-modify-write update with optimistic concurrency; returns the new version ID.
     *
     * <p>The request is the complete desired state of the managed fields and is validated before any HTTP
     * request. One {@code organization-update} operation then reads the current resource, rejects a version
     * other than {@code expectedVersionId} locally as HTTP 412 (no PUT), merges only the managed fields into
     * a copy of the current resource and sends {@code PUT [base]/Organization/[id]} with
     * {@code If-Match: "expectedVersionId"}, so the server rejects a concurrent change (409/412).
     * A resource with resource-level modifierExtension or implicitRules is never rewritten. There are no
     * automatic retries: after 409/412 read again and decide again; after another failure the PUT may
     * already have been committed.
     */
    public String updateOrganization(String id, String expectedVersionId, OrganizationWriteRequest request) {
        requireId(id);
        if (expectedVersionId == null || !expectedVersionId.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid expected FHIR Organization version ID is required");
        }
        Organization desired = mapper.toResource(request);
        if (id.equals(request.partOfOrganizationId())) {
            throw new IllegalArgumentException("An Organization cannot be part of itself");
        }
        return requestExecutor.execute("organization-update", () -> {
            Organization current = fhirClient.read().resource(Organization.class).withId(id).execute();
            if (!expectedVersionId.equals(currentVersion(current, id))) {
                throw new PreconditionFailedException("Organization version does not match the expected version");
            }
            if (!mapper.isSafelyUpdatable(current)) {
                throw new DataFormatException("Organization has modifier semantics that cannot be updated safely");
            }
            Organization merged = mapper.mergeForUpdate(current, desired, id);
            // A versioned target ID makes HAPI send If-Match: "expectedVersionId"; the body stays unversioned.
            return updatedVersion(fhirClient.update().resource(merged)
                    .withId(new IdType("Organization", id, expectedVersionId)).execute(), id, expectedVersionId);
        });
    }

    private void requireId(String id) {
        if (id == null || !id.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Organization ID is required");
        }
    }

    /** The server version of the requested resource; a missing or inconsistent version fails closed. */
    private String currentVersion(Organization current, String id) {
        if (current == null || !id.equals(current.getIdElement().getIdPart())) throw invalidUpdate();
        String metaVersion = current.hasMeta() ? current.getMeta().getVersionId() : null;
        String idVersion = current.getIdElement().getVersionIdPart();
        if (metaVersion == null || !metaVersion.matches(LOGICAL_ID)
                || idVersion != null && !idVersion.equals(metaVersion)) throw invalidUpdate();
        return metaVersion;
    }

    /**
     * The new version from Content-Location/Location, or else from the returned resource; both must name
     * the requested Organization, agree with each other and differ from the expected version.
     */
    private String updatedVersion(MethodOutcome outcome, String id, String expectedVersionId) {
        if (outcome == null || Boolean.TRUE.equals(outcome.getCreated())) throw invalidUpdate();
        String headerVersion = null;
        IIdType location = outcome.getId();
        if (location != null && !location.isEmpty()) {
            if (!"Organization".equals(location.getResourceType()) || !id.equals(location.getIdPart())) {
                throw invalidUpdate();
            }
            headerVersion = location.getVersionIdPart();
        }
        String bodyVersion = null;
        if (outcome.getResource() != null) {
            if (!(outcome.getResource() instanceof Organization returned)
                    || returned.getIdElement().hasIdPart() && !id.equals(returned.getIdElement().getIdPart())) {
                throw invalidUpdate();
            }
            String metaVersion = returned.hasMeta() ? returned.getMeta().getVersionId() : null;
            String idVersion = returned.getIdElement().getVersionIdPart();
            if (metaVersion != null && idVersion != null && !metaVersion.equals(idVersion)) throw invalidUpdate();
            bodyVersion = metaVersion != null ? metaVersion : idVersion;
        }
        String version = headerVersion != null ? headerVersion : bodyVersion;
        if (headerVersion != null && bodyVersion != null && !headerVersion.equals(bodyVersion)
                || version == null || !version.matches(LOGICAL_ID) || Objects.equals(version, expectedVersionId)) {
            throw invalidUpdate();
        }
        return version;
    }

    private DataFormatException invalidUpdate() {
        return new DataFormatException("Invalid or unsafe Organization update response");
    }
}
