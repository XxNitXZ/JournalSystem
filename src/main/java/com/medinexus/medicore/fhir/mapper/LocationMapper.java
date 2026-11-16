package com.medinexus.medicore.fhir.mapper;

import java.math.BigDecimal;
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
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.location.dto.LocationDto;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.organization.dto.OrganizationDto;

@Component
public class LocationMapper {

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);

    /**
     * Read projection; references are preserved verbatim. A position must carry both longitude and
     * latitude (FHIR 1..1 each); coordinates are projected exactly, without range checks.
     */
    public LocationDto toDto(Location location) {
        if (location == null) throw new IllegalArgumentException("Location is required");
        if (location.hasPosition() && (location.getPosition().getLongitude() == null
                || location.getPosition().getLatitude() == null)) {
            throw new IllegalArgumentException("Location position requires longitude and latitude");
        }
        String versionId = location.hasMeta() && location.getMeta().hasVersionId()
                ? location.getMeta().getVersionId() : location.getIdElement().getVersionIdPart();
        return new LocationDto(location.getIdElement().getIdPart(), versionId,
                location.getIdentifier().stream().map(this::identifier).toList(),
                location.hasStatus() ? location.getStatusElement().getValueAsString() : null,
                location.hasOperationalStatus() ? coding(location.getOperationalStatus()) : null,
                location.getName(),
                values(location.getAlias()),
                location.getDescription(),
                location.hasMode() ? location.getModeElement().getValueAsString() : null,
                location.getType().stream().map(this::concept).toList(),
                location.getTelecom().stream().map(this::contactPoint).toList(),
                location.hasAddress() ? address(location.getAddress()) : null,
                location.hasPhysicalType() ? concept(location.getPhysicalType()) : null,
                location.hasPosition() ? position(location.getPosition()) : null,
                location.hasManagingOrganization() ? reference(location.getManagingOrganization()) : null,
                location.hasPartOf() ? reference(location.getPartOf()) : null);
    }

    /**
     * Validates the entire write input before the client starts an HTTP request; no ID and no meta are set.
     * FHIR R4 requires no Location element, so an empty request is valid.
     */
    public Location toResource(LocationWriteRequest request) {
        if (request == null) throw invalid();
        Location resource = new Location();
        Set<ObservationDto.Identifier> identifiers = new HashSet<>();
        for (ObservationDto.Identifier identifier : request.identifiers()) {
            if (identifier == null || !nonblank(identifier.value())
                    || identifier.system() != null && !absoluteUri(identifier.system())
                    || !identifiers.add(identifier)) throw invalid();
            resource.addIdentifier().setSystem(identifier.system()).setValue(identifier.value());
        }
        if (request.status() != null) resource.setStatus(Location.LocationStatus.fromCode(request.status().code()));
        if (request.name() != null) {
            if (!nonblank(request.name())) throw invalid();
            resource.setName(request.name());
        }
        Set<String> aliases = new HashSet<>();
        for (String alias : request.aliases()) {
            if (!nonblank(alias) || !aliases.add(alias)) throw invalid();
            resource.addAlias(alias);
        }
        if (request.description() != null) {
            if (!nonblank(request.description())) throw invalid();
            resource.setDescription(request.description());
        }
        if (request.mode() != null) resource.setMode(Location.LocationMode.fromCode(request.mode().code()));
        request.types().forEach(type -> resource.addType(createConcept(type)));
        request.telecoms().forEach(telecom -> resource.addTelecom(createContactPoint(telecom)));
        if (request.address() != null) resource.setAddress(createAddress(request.address()));
        if (request.physicalType() != null) resource.setPhysicalType(createConcept(request.physicalType()));
        if (request.position() != null) resource.setPosition(createPosition(request.position()));
        if (request.managingOrganizationId() != null) {
            if (!request.managingOrganizationId().matches(LOGICAL_ID)) throw invalid();
            resource.setManagingOrganization(new Reference("Organization/" + request.managingOrganizationId()));
        }
        if (request.partOfLocationId() != null) {
            if (!request.partOfLocationId().matches(LOGICAL_ID)) throw invalid();
            resource.setPartOf(new Reference("Location/" + request.partOfLocationId()));
        }
        return resource;
    }

    /**
     * False when the current resource carries modifier semantics MediCore cannot interpret: resource-level
     * implicitRules or modifierExtension, or a modifierExtension on the managed position element.
     * Such a resource must not be rewritten.
     */
    public boolean isSafelyUpdatable(Location current) {
        return current != null && !current.hasImplicitRules() && !current.hasModifierExtension()
                && !(current.hasPosition() && current.getPosition().hasModifierExtension());
    }

    /**
     * Returns a new resource for a versioned PUT: a deep copy of {@code current} in which only the managed
     * fields (identifier, status, name, alias, description, mode, type, telecom, address, physicalType,
     * position, managingOrganization, partOf) take the values of {@code desired}. Every unmanaged element
     * (operationalStatus, hoursOfOperation, availabilityExceptions, endpoint, extensions, contained,
     * language, meta tags/profiles/security/source, non-generated narrative) is preserved. A desired entry
     * that projects exactly like an existing entry keeps the existing entry, so hidden subfields such as
     * Identifier.type/period, ContactPoint.period, Address.period and element extensions survive. A kept
     * reference keeps its display. Server-assigned meta.versionId and meta.lastUpdated and a generated
     * narrative are removed. The ID is unversioned (HAPI would otherwise encode its version as
     * meta.versionId); the client supplies the expected version separately as If-Match. Neither input is
     * modified.
     */
    public Location mergeForUpdate(Location current, Location desired, String id) {
        if (desired == null || id == null || !id.matches(LOGICAL_ID) || !isSafelyUpdatable(current)) {
            throw new IllegalArgumentException("Invalid Location update input");
        }
        Location merged = current.copy();
        merged.setIdentifier(keep(merged.getIdentifier(), desired.getIdentifier(), this::identifier, Identifier::copy));
        merged.setStatusElement(desired.hasStatus()
                ? keepPrimitive(merged.hasStatus() ? merged.getStatusElement() : null, desired.getStatusElement()) : null);
        merged.setNameElement(desired.hasName()
                ? keepPrimitive(merged.hasName() ? merged.getNameElement() : null, desired.getNameElement()) : null);
        merged.setAlias(keep(merged.getAlias(), desired.getAlias(), StringType::getValue, StringType::copy));
        merged.setDescriptionElement(desired.hasDescription()
                ? keepPrimitive(merged.hasDescription() ? merged.getDescriptionElement() : null,
                        desired.getDescriptionElement()) : null);
        merged.setModeElement(desired.hasMode()
                ? keepPrimitive(merged.hasMode() ? merged.getModeElement() : null, desired.getModeElement()) : null);
        merged.setType(keep(merged.getType(), desired.getType(), this::concept, CodeableConcept::copy));
        merged.setTelecom(keep(merged.getTelecom(), desired.getTelecom(), this::contactPoint, ContactPoint::copy));
        merged.setAddress(keepSingle(merged.hasAddress() ? merged.getAddress() : null,
                desired.hasAddress() ? desired.getAddress() : null, this::address, Address::copy));
        merged.setPhysicalType(keepSingle(merged.hasPhysicalType() ? merged.getPhysicalType() : null,
                desired.hasPhysicalType() ? desired.getPhysicalType() : null, this::concept, CodeableConcept::copy));
        merged.setPosition(keepSingle(merged.hasPosition() ? merged.getPosition() : null,
                desired.hasPosition() ? desired.getPosition() : null, this::position, Location.LocationPositionComponent::copy));
        merged.setManagingOrganization(keepReference(
                merged.hasManagingOrganization() ? merged.getManagingOrganization() : null,
                desired.hasManagingOrganization() ? desired.getManagingOrganization() : null));
        merged.setPartOf(keepReference(merged.hasPartOf() ? merged.getPartOf() : null,
                desired.hasPartOf() ? desired.getPartOf() : null));
        if (merged.hasText() && merged.getText().getStatus() == Narrative.NarrativeStatus.GENERATED) {
            merged.setText(null);
        }
        if (merged.hasMeta()) {
            merged.getMeta().setVersionIdElement(null);
            merged.getMeta().setLastUpdatedElement(null);
            if (merged.getMeta().isEmpty()) merged.setMeta(null);
        }
        merged.setId(new IdType("Location", id));
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

    /** A single managed element: kept when its projection is unchanged, replaced otherwise, removed when absent. */
    private <T extends Base> T keepSingle(T current, T desired, Function<T, ?> projection, UnaryOperator<T> copy) {
        if (desired == null) return null;
        return current != null && Objects.equals(projection.apply(current), projection.apply(desired))
                ? current : copy.apply(desired);
    }

    /** A reference with the same reference string is kept, including its display, type and identifier. */
    private Reference keepReference(Reference current, Reference desired) {
        if (desired == null) return null;
        return current != null && Objects.equals(current.getReference(), desired.getReference()) ? current : desired.copy();
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

    private LocationDto.Position position(Location.LocationPositionComponent position) {
        return new LocationDto.Position(position.getLongitude(), position.getLatitude(), position.getAltitude());
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

    /** FHIR cpt-2 (a value needs a system): both are required; rank is a positiveInt. */
    private ContactPoint createContactPoint(LocationWriteRequest.ContactPoint telecom) {
        if (telecom == null || telecom.system() == null || !nonblank(telecom.value())
                || telecom.rank() != null && telecom.rank() < 1) throw invalid();
        ContactPoint result = new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.fromCode(telecom.system().code()))
                .setValue(telecom.value());
        if (telecom.use() != null) result.setUse(ContactPoint.ContactPointUse.fromCode(telecom.use().code()));
        if (telecom.rank() != null) result.setRank(telecom.rank());
        return result;
    }

    /** An address needs content (text, a line or a part); strings must not be blank. */
    private Address createAddress(LocationWriteRequest.Address source) {
        if (source.lines().stream().anyMatch(line -> !nonblank(line))) throw invalid();
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

    /** WGS84: longitude and latitude are required (1..1) and within range; altitude is optional. Exact decimals. */
    private Location.LocationPositionComponent createPosition(LocationDto.Position source) {
        if (source.longitude() == null || source.latitude() == null
                || source.longitude().abs().compareTo(MAX_LONGITUDE) > 0
                || source.latitude().abs().compareTo(MAX_LATITUDE) > 0) throw invalid();
        Location.LocationPositionComponent result = new Location.LocationPositionComponent()
                .setLongitude(source.longitude()).setLatitude(source.latitude());
        if (source.altitude() != null) result.setAltitude(source.altitude());
        return result;
    }

    private boolean nonblank(String value) { return value != null && !value.isBlank(); }

    private boolean absoluteUri(String value) {
        if (!nonblank(value)) return false;
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Location write request");
    }
}
