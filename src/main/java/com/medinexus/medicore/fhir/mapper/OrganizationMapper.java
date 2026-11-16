package com.medinexus.medicore.fhir.mapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;

@Component
public class OrganizationMapper {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    /** Read projection; enforces FHIR org-1 (a name or an identifier). partOf is preserved verbatim. */
    public OrganizationDto toDto(Organization organization) {
        if (organization == null || !organization.hasName() && !organization.hasIdentifier()) {
            throw new IllegalArgumentException("Organization name or identifier is required");
        }
        String versionId = organization.hasMeta() && organization.getMeta().hasVersionId()
                ? organization.getMeta().getVersionId()
                : organization.getIdElement().getVersionIdPart();
        return new OrganizationDto(organization.getIdElement().getIdPart(), versionId,
                organization.getIdentifier().stream().map(this::identifier).toList(),
                organization.hasActive() ? organization.getActiveElement().getValue() : null,
                organization.getType().stream().map(this::concept).toList(),
                organization.getName(),
                values(organization.getAlias()),
                organization.getTelecom().stream().map(this::contactPoint).toList(),
                organization.getAddress().stream().map(this::address).toList(),
                organization.hasPartOf() ? reference(organization.getPartOf()) : null);
    }

    /** Validates the entire write input before the client starts an HTTP request; no ID and no meta are set. */
    public Organization toResource(OrganizationWriteRequest request) {
        if (request == null) throw invalid();
        Organization resource = new Organization();
        Set<ObservationDto.Identifier> identifiers = new HashSet<>();
        for (ObservationDto.Identifier identifier : request.identifiers()) {
            if (identifier == null || !nonblank(identifier.value())
                    || identifier.system() != null && !absoluteUri(identifier.system())
                    || !identifiers.add(identifier)) throw invalid();
            resource.addIdentifier().setSystem(identifier.system()).setValue(identifier.value());
        }
        if (request.active() != null) resource.setActive(request.active());
        request.types().forEach(type -> resource.addType(createConcept(type)));
        if (request.name() != null) {
            if (!nonblank(request.name())) throw invalid();
            resource.setName(request.name());
        }
        // FHIR org-1: the organization SHALL at least have a name or an identifier.
        if (request.name() == null && request.identifiers().isEmpty()) throw invalid();
        Set<String> aliases = new HashSet<>();
        for (String alias : request.aliases()) {
            if (!nonblank(alias) || !aliases.add(alias)) throw invalid();
            resource.addAlias(alias);
        }
        request.telecoms().forEach(telecom -> resource.addTelecom(createContactPoint(telecom)));
        request.addresses().forEach(address -> resource.addAddress(createAddress(address)));
        if (request.partOfOrganizationId() != null) {
            if (!request.partOfOrganizationId().matches(LOGICAL_ID)) throw invalid();
            resource.setPartOf(new Reference("Organization/" + request.partOfOrganizationId()));
        }
        return resource;
    }

    /**
     * False when the current resource carries resource-level modifier semantics MediCore cannot interpret
     * (implicitRules or modifierExtension). Such a resource must not be rewritten.
     */
    public boolean isSafelyUpdatable(Organization current) {
        return current != null && !current.hasImplicitRules() && !current.hasModifierExtension();
    }

    /**
     * Returns a new resource for a versioned PUT: a deep copy of {@code current} in which only the managed
     * fields (identifier, active, type, name, alias, telecom, address, partOf) take the values of
     * {@code desired}. Every unmanaged element (contact, endpoint, extensions, contained, language, meta
     * tags/profiles/security/source, non-generated narrative) is preserved. A desired entry that projects
     * exactly like an existing entry keeps the existing entry, so hidden subfields such as
     * Identifier.type/period, ContactPoint.period, Address.period and element extensions survive.
     * Server-assigned meta.versionId and meta.lastUpdated and a generated narrative are removed. The ID is
     * unversioned (HAPI would otherwise encode its version as meta.versionId); the client supplies the
     * expected version separately as If-Match. Neither input is modified.
     */
    public Organization mergeForUpdate(Organization current, Organization desired, String id) {
        if (desired == null || id == null || !id.matches(LOGICAL_ID) || !isSafelyUpdatable(current)) {
            throw new IllegalArgumentException("Invalid Organization update input");
        }
        Organization merged = current.copy();
        merged.setIdentifier(keep(merged.getIdentifier(), desired.getIdentifier(), this::identifier, Identifier::copy));
        merged.setActiveElement(desired.hasActive()
                ? keepPrimitive(merged.hasActive() ? merged.getActiveElement() : null, desired.getActiveElement()) : null);
        merged.setType(keep(merged.getType(), desired.getType(), this::concept, CodeableConcept::copy));
        merged.setNameElement(desired.hasName()
                ? keepPrimitive(merged.hasName() ? merged.getNameElement() : null, desired.getNameElement()) : null);
        merged.setAlias(keep(merged.getAlias(), desired.getAlias(), StringType::getValue, StringType::copy));
        merged.setTelecom(keep(merged.getTelecom(), desired.getTelecom(), this::contactPoint, ContactPoint::copy));
        merged.setAddress(keep(merged.getAddress(), desired.getAddress(), this::address, Address::copy));
        if (!desired.hasPartOf()) {
            merged.setPartOf(null);
        } else if (!merged.hasPartOf()
                || !Objects.equals(merged.getPartOf().getReference(), desired.getPartOf().getReference())) {
            merged.setPartOf(desired.getPartOf().copy());
        }
        if (merged.hasText() && merged.getText().getStatus() == Narrative.NarrativeStatus.GENERATED) {
            merged.setText(null);
        }
        if (merged.hasMeta()) {
            merged.getMeta().setVersionIdElement(null);
            merged.getMeta().setLastUpdatedElement(null);
            if (merged.getMeta().isEmpty()) merged.setMeta(null);
        }
        merged.setId(new IdType("Organization", id));
        return merged;
    }

    /** Desired order; an unchanged entry (equal projection) reuses an existing entry, each at most once. */
    private <T> List<T> keep(List<T> current, List<T> desired, Function<T, ?> projection, UnaryOperator<T> copy) {
        List<T> unused = new ArrayList<>(current);
        List<T> result = new ArrayList<>();
        for (T wanted : desired) {
            Object key = projection.apply(wanted);
            int match = -1;
            for (int i = 0; i < unused.size() && match < 0; i++) {
                if (Objects.equals(key, projection.apply(unused.get(i)))) match = i;
            }
            result.add(match >= 0 ? unused.remove(match) : copy.apply(wanted));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T extends PrimitiveType<?>> T keepPrimitive(T current, T desired) {
        return current != null && Objects.equals(current.getValue(), desired.getValue()) ? current : (T) desired.copy();
    }

    private ObservationDto.Identifier identifier(Identifier identifier) {
        return new ObservationDto.Identifier(identifier.getSystem(), identifier.getValue());
    }

    private OrganizationDto.ContactPoint contactPoint(ContactPoint telecom) {
        return new OrganizationDto.ContactPoint(telecom.hasSystem() ? telecom.getSystemElement().getValueAsString() : null,
                telecom.getValue(), telecom.hasUse() ? telecom.getUseElement().getValueAsString() : null,
                telecom.hasRank() ? telecom.getRankElement().getValue() : null);
    }

    private OrganizationDto.Address address(Address address) {
        return new OrganizationDto.Address(address.hasUse() ? address.getUseElement().getValueAsString() : null,
                address.hasType() ? address.getTypeElement().getValueAsString() : null, address.getText(),
                values(address.getLine()), address.getCity(), address.getDistrict(), address.getState(),
                address.getPostalCode(), address.getCountry());
    }

    private List<String> values(List<StringType> values) {
        return values.stream().map(StringType::getValue).filter(Objects::nonNull).toList();
    }

    private SubjectReference reference(Reference reference) {
        return new SubjectReference(reference.getReference(), reference.getType(), reference.getDisplay(),
                reference.hasIdentifier() ? identifier(reference.getIdentifier()) : null);
    }

    private ObservationDto.Coding coding(Coding coding) {
        return new ObservationDto.Coding(coding.getSystem(), coding.getVersion(), coding.getCode(), coding.getDisplay(),
                coding.hasUserSelected() ? coding.getUserSelected() : null);
    }

    private Concept concept(CodeableConcept source) {
        return new Concept(source.getText(), source.getCoding().stream().map(this::coding).toList());
    }

    private CodeableConcept createConcept(Concept source) {
        if (source == null || !nonblank(source.text()) && source.coding().isEmpty()) throw invalid();
        if (source.text() != null && !nonblank(source.text())) throw invalid();
        CodeableConcept result = new CodeableConcept().setText(source.text());
        for (var coding : source.coding()) {
            if (coding == null || !absoluteUri(coding.system()) || !nonblank(coding.code())) throw invalid();
            var mapped = result.addCoding().setSystem(coding.system()).setVersion(coding.version())
                    .setCode(coding.code()).setDisplay(coding.display());
            if (coding.userSelected() != null) mapped.setUserSelected(coding.userSelected());
        }
        return result;
    }

    /** FHIR cpt-2 (a value needs a system): both are required; rank is a positiveInt. org-2 holds by type. */
    private ContactPoint createContactPoint(OrganizationWriteRequest.ContactPoint telecom) {
        if (telecom == null || telecom.system() == null || !nonblank(telecom.value())
                || telecom.rank() != null && telecom.rank() < 1) throw invalid();
        ContactPoint result = new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.fromCode(telecom.system().code()))
                .setValue(telecom.value());
        if (telecom.use() != null) result.setUse(ContactPoint.ContactPointUse.fromCode(telecom.use().code()));
        if (telecom.rank() != null) result.setRank(telecom.rank());
        return result;
    }

    /** An address needs content (text, a line or a part); strings must not be blank. org-3 holds by type. */
    private Address createAddress(OrganizationWriteRequest.Address source) {
        if (source == null || source.lines().stream().anyMatch(line -> !nonblank(line))) throw invalid();
        List<String> parts = Arrays.asList(source.text(), source.city(), source.district(), source.state(),
                source.postalCode(), source.country());
        if (parts.stream().anyMatch(part -> part != null && !nonblank(part))
                || source.lines().isEmpty() && parts.stream().allMatch(Objects::isNull)) throw invalid();
        Address result = new Address().setText(source.text()).setCity(source.city()).setDistrict(source.district())
                .setState(source.state()).setPostalCode(source.postalCode()).setCountry(source.country());
        if (source.use() != null) result.setUse(Address.AddressUse.fromCode(source.use().code()));
        if (source.type() != null) result.setType(Address.AddressType.fromCode(source.type().code()));
        source.lines().forEach(result::addLine);
        return result;
    }

    private boolean nonblank(String value) { return value != null && !value.isBlank(); }

    private boolean absoluteUri(String value) {
        if (!nonblank(value)) return false;
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Organization write request");
    }
}
