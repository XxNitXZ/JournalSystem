package com.medinexus.medicore.fhir.client;

import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Strict interpretation of FHIR references against the configured server: only {@code Type/id} (relative or absolute
 * on the configured base), without version and with an agreeing declared type, is resolved. References to other
 * servers, versioned, contained or identifier-only references never are.
 */
@Component
public class FhirReferences {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    private final IGenericClient fhirClient;

    public FhirReferences(IGenericClient fhirClient) {
        this.fhirClient = fhirClient;
    }

    /** The logical ID of a reference to a resource of this type on the configured server, otherwise null. */
    public String logicalId(Reference reference, String type) {
        if (reference == null || !reference.hasReference()) return null;
        if (reference.hasType() && !type.equals(reference.getType())
                && !("http://hl7.org/fhir/StructureDefinition/" + type).equals(reference.getType())) return null;
        String value = reference.getReference();
        String absolutePrefix = fhirClient.getServerBase() + "/";
        if (value.startsWith(absolutePrefix)) value = value.substring(absolutePrefix.length());
        if (!value.startsWith(type + "/")) return null;
        String id = value.substring(type.length() + 1);
        return id.matches(LOGICAL_ID) ? id : null;
    }

    /** True if the reference is exactly {@code {type}/{id}} on the configured server (see {@link #logicalId}). */
    public boolean refersTo(Reference reference, String type, String id) {
        return id != null && id.equals(logicalId(reference, type));
    }
}
