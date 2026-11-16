package com.medinexus.medicore.fhir.client;

import java.util.Objects;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Location;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;

import com.medinexus.medicore.fhir.mapper.LocationMapper;
import com.medinexus.medicore.location.dto.LocationWriteRequest;

@Component
public class FhirLocationClient {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    private final LocationMapper mapper;
    private final IGenericClient fhirClient;
    private final FhirRequestExecutor requestExecutor;

    public FhirLocationClient(IGenericClient fhirClient, FhirRequestExecutor requestExecutor, LocationMapper mapper) {
        this.mapper = mapper;
        this.fhirClient = fhirClient;
        this.requestExecutor = requestExecutor;
    }

    /** Reads GET [base]/Location/[id]. A missing resource remains a translated HTTP 404. */
    public Location readLocation(String id) {
        requireId(id);
        return requestExecutor.execute("location-read", () -> fhirClient.read()
                .resource(Location.class).withId(id).execute());
    }

    /** Internal POST [base]/Location. A failed response may follow a committed write; do not retry blindly. */
    public String createLocation(LocationWriteRequest request) {
        Location resource = mapper.toResource(request);
        return requestExecutor.execute("location-create", () -> {
            var outcome = fhirClient.create().resource(resource).execute();
            var id = outcome == null ? null : outcome.getId();
            if (outcome == null || !Boolean.TRUE.equals(outcome.getCreated()) || id == null
                    || !"Location".equals(id.getResourceType()) || id.getIdPart() == null
                    || !id.getIdPart().matches(LOGICAL_ID)) {
                throw new DataFormatException("Location creation response did not confirm a valid assigned ID");
            }
            return id.getIdPart();
        });
    }

    /**
     * Read-modify-write update with optimistic concurrency; returns the new version ID.
     *
     * <p>The request is the complete desired state of the managed fields and is validated before any HTTP
     * request; partOf must not name this Location. One {@code location-update} operation then reads the
     * current resource, rejects a version other than {@code expectedVersionId} locally as HTTP 412 (no PUT),
     * merges only the managed fields into a copy of the current resource and sends
     * {@code PUT [base]/Location/[id]} with {@code If-Match: "expectedVersionId"}, so the server rejects a
     * concurrent change (409/412). A resource with modifier semantics MediCore cannot interpret is never
     * rewritten. There are no automatic retries: after 409/412 read again and decide again; after another
     * failure the PUT may already have been committed.
     */
    public String updateLocation(String id, String expectedVersionId, LocationWriteRequest request) {
        requireId(id);
        if (expectedVersionId == null || !expectedVersionId.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid expected FHIR Location version ID is required");
        }
        Location desired = mapper.toResource(request);
        if (id.equals(request.partOfLocationId())) {
            throw new IllegalArgumentException("A Location cannot be part of itself");
        }
        return requestExecutor.execute("location-update", () -> {
            Location current = fhirClient.read().resource(Location.class).withId(id).execute();
            if (!expectedVersionId.equals(currentVersion(current, id))) {
                throw new PreconditionFailedException("Location version does not match the expected version");
            }
            if (!mapper.isSafelyUpdatable(current)) {
                throw new DataFormatException("Location has modifier semantics that cannot be updated safely");
            }
            Location merged = mapper.mergeForUpdate(current, desired, id);
            // A versioned target ID makes HAPI send If-Match: "expectedVersionId"; the body stays unversioned.
            return updatedVersion(fhirClient.update().resource(merged)
                    .withId(new IdType("Location", id, expectedVersionId)).execute(), id, expectedVersionId);
        });
    }

    private void requireId(String id) {
        if (id == null || !id.matches(LOGICAL_ID)) {
            throw new IllegalArgumentException("A valid logical FHIR Location ID is required");
        }
    }

    /** The server version of the requested resource; a missing or inconsistent version fails closed. */
    private String currentVersion(Location current, String id) {
        if (current == null || !id.equals(current.getIdElement().getIdPart())) throw invalidUpdate();
        String metaVersion = current.hasMeta() ? current.getMeta().getVersionId() : null;
        String idVersion = current.getIdElement().getVersionIdPart();
        if (metaVersion == null || !metaVersion.matches(LOGICAL_ID)
                || idVersion != null && !idVersion.equals(metaVersion)) throw invalidUpdate();
        return metaVersion;
    }

    /**
     * The new version from Content-Location/Location, or else from the returned resource; both must name
     * the requested Location, agree with each other and differ from the expected version.
     */
    private String updatedVersion(MethodOutcome outcome, String id, String expectedVersionId) {
        if (outcome == null || Boolean.TRUE.equals(outcome.getCreated())) throw invalidUpdate();
        String headerVersion = null;
        IIdType location = outcome.getId();
        if (location != null && !location.isEmpty()) {
            if (!"Location".equals(location.getResourceType()) || !id.equals(location.getIdPart())) {
                throw invalidUpdate();
            }
            headerVersion = location.getVersionIdPart();
        }
        String bodyVersion = null;
        if (outcome.getResource() != null) {
            if (!(outcome.getResource() instanceof Location returned)
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
        return new DataFormatException("Invalid or unsafe Location update response");
    }
}
