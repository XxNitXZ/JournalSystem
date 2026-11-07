package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.Location;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.medinexus.medicore.location.dto.LocationDto;
import com.medinexus.medicore.location.dto.LocationDto.Position;
import com.medinexus.medicore.location.dto.LocationWriteRequest;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Address;
import com.medinexus.medicore.location.dto.LocationWriteRequest.AddressType;
import com.medinexus.medicore.location.dto.LocationWriteRequest.AddressUse;
import com.medinexus.medicore.location.dto.LocationWriteRequest.ContactPoint;
import com.medinexus.medicore.location.dto.LocationWriteRequest.ContactPointSystem;
import com.medinexus.medicore.location.dto.LocationWriteRequest.ContactPointUse;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Mode;
import com.medinexus.medicore.location.dto.LocationWriteRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.organization.dto.OrganizationDto;

import ca.uhn.fhir.context.FhirContext;

/** Synthetic Location data only; no HTTP. */
class LocationMapperTest {

    private static final FhirContext CONTEXT = FhirContext.forR4();
    private static final String TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-RoleCode";
    private static final String PHYSICAL_SYSTEM = "http://terminology.hl7.org/CodeSystem/location-physical-type";

    /** Every managed field with hidden subfields, plus unmanaged elements that an update must preserve. */
    static final String FULL = """
            {"resourceType":"Location","id":"loc-1",
             "meta":{"versionId":"3","lastUpdated":"2026-06-01T10:00:00.000+00:00","source":"#synthetic-source",
               "profile":["urn:synthetic:profile"],"tag":[{"system":"urn:synthetic:tag","code":"t"}],
               "security":[{"system":"urn:synthetic:security","code":"s"}]},
             "language":"sv",
             "text":{"status":"additional","div":"<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Synthetic narrative</div>"},
             "contained":[{"resourceType":"Basic","id":"c1","code":{"text":"Synthetic contained"}}],
             "extension":[{"url":"urn:synthetic:extension","valueReference":{"reference":"#c1"}}],
             "identifier":[{"use":"official","type":{"text":"Synthetic identifier type"},"system":"urn:synthetic:location",
               "value":"LOC-1","period":{"start":"2020-01-01"}}],
             "status":"active",
             "_status":{"extension":[{"url":"urn:synthetic:status","valueString":"kept"}]},
             "operationalStatus":{"system":"http://terminology.hl7.org/CodeSystem/v2-0116","code":"O","display":"Occupied"},
             "name":"Synthetic Ward",
             "alias":["Ward A","Ward B"],
             "description":"Synthetic ward description",
             "mode":"instance",
             "type":[{"coding":[{"extension":[{"url":"urn:synthetic:coding","valueString":"kept"}],
               "system":"http://terminology.hl7.org/CodeSystem/v3-RoleCode","code":"HOSP","display":"Hospital"}],"text":"Hospital"}],
             "telecom":[{"system":"phone","value":"+46000000002","use":"work","rank":1,"period":{"start":"2020-01-01"}},
               {"system":"email","value":"ward@example.test"}],
             "address":{"use":"work","type":"physical","text":"Synthetic street 2","line":["Synthetic street 2"],
               "city":"Synthetic City","district":"District","state":"State","postalCode":"22222","country":"SE",
               "period":{"start":"2020-01-01"}},
             "physicalType":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/location-physical-type","code":"wa",
               "display":"Ward"}],"text":"Ward"},
             "position":{"longitude":18.0686000,"latitude":59.3293000,"altitude":28.50},
             "managingOrganization":{"reference":"Organization/org-1","display":"Synthetic Clinic"},
             "partOf":{"reference":"Location/parent-loc","display":"Synthetic Building"},
             "hoursOfOperation":[{"daysOfWeek":["mon","tue"],"openingTime":"08:00:00","closingTime":"17:00:00"}],
             "availabilityExceptions":"Synthetic availability exceptions",
             "endpoint":[{"reference":"Endpoint/ep-1"}]}
            """;

    private final LocationMapper mapper = new LocationMapper();

    static Location parse(String json) {
        return CONTEXT.newJsonParser().parseResource(Location.class, json);
    }

    static String encode(Location location) {
        return CONTEXT.newJsonParser().encodeResourceToString(location);
    }

    static Concept hospitalType() {
        return new Concept("Hospital", List.of(new Coding(TYPE_SYSTEM, null, "HOSP", "Hospital", null)));
    }

    static Concept wardPhysicalType() {
        return new Concept("Ward", List.of(new Coding(PHYSICAL_SYSTEM, null, "wa", "Ward", null)));
    }

    static Position fullPosition() {
        return new Position(new BigDecimal("18.0686000"), new BigDecimal("59.3293000"), new BigDecimal("28.50"));
    }

    /** The write request that describes FULL's managed fields unchanged (as an edit of its DTO would). */
    static LocationWriteRequest unchangedFullRequest() {
        return new LocationWriteRequest(List.of(new Identifier("urn:synthetic:location", "LOC-1")), Status.ACTIVE,
                "Synthetic Ward", List.of("Ward A", "Ward B"), "Synthetic ward description", Mode.INSTANCE,
                List.of(hospitalType()),
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000002", ContactPointUse.WORK, 1),
                        new ContactPoint(ContactPointSystem.EMAIL, "ward@example.test", null, null)),
                new Address(AddressUse.WORK, AddressType.PHYSICAL, "Synthetic street 2", List.of("Synthetic street 2"),
                        "Synthetic City", "District", "State", "22222", "SE"),
                wardPhysicalType(), fullPosition(), "org-1", "parent-loc");
    }

    private static LocationWriteRequest empty() {
        return new LocationWriteRequest(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static LocationWriteRequest named(String name) {
        return new LocationWriteRequest(null, null, name, null, null, null, null, null, null, null, null, null, null);
    }

    private static LocationWriteRequest described(String description) {
        return new LocationWriteRequest(null, null, null, null, description, null, null, null, null, null, null, null, null);
    }

    private static LocationWriteRequest withIdentifiers(Identifier... identifiers) {
        return new LocationWriteRequest(Arrays.asList(identifiers), null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static LocationWriteRequest withAliases(String... aliases) {
        return new LocationWriteRequest(null, null, null, Arrays.asList(aliases), null, null, null, null, null, null,
                null, null, null);
    }

    private static LocationWriteRequest withType(Concept type) {
        return new LocationWriteRequest(null, null, null, null, null, null, Arrays.asList(type), null, null, null,
                null, null, null);
    }

    private static LocationWriteRequest withPhysicalType(Concept physicalType) {
        return new LocationWriteRequest(null, null, null, null, null, null, null, null, null, physicalType,
                null, null, null);
    }

    private static LocationWriteRequest withTelecom(ContactPoint telecom) {
        return new LocationWriteRequest(null, null, null, null, null, null, null, Arrays.asList(telecom), null, null,
                null, null, null);
    }

    private static LocationWriteRequest withAddress(Address address) {
        return new LocationWriteRequest(null, null, null, null, null, null, null, null, address, null, null, null, null);
    }

    static LocationWriteRequest at(String longitude, String latitude, String altitude) {
        return new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null,
                new Position(longitude == null ? null : new BigDecimal(longitude),
                        latitude == null ? null : new BigDecimal(latitude),
                        altitude == null ? null : new BigDecimal(altitude)), null, null);
    }

    private static LocationWriteRequest references(String managingOrganizationId, String partOfLocationId) {
        return new LocationWriteRequest(null, null, "N", null, null, null, null, null, null, null, null,
                managingOrganizationId, partOfLocationId);
    }

    private static Address address(String text, List<String> lines, String city) {
        return new Address(AddressUse.WORK, null, text, lines, city, null, null, null, null);
    }

    @Test
    void projectsEveryFieldWithServerVersionExactCoordinatesAndVerbatimReferences() {
        LocationDto dto = mapper.toDto(parse(FULL));

        assertThat(dto).isEqualTo(new LocationDto("loc-1", "3", List.of(new Identifier("urn:synthetic:location", "LOC-1")),
                "active", new Coding("http://terminology.hl7.org/CodeSystem/v2-0116", null, "O", "Occupied", null),
                "Synthetic Ward", List.of("Ward A", "Ward B"), "Synthetic ward description", "instance",
                List.of(hospitalType()),
                List.of(new OrganizationDto.ContactPoint("phone", "+46000000002", "work", 1),
                        new OrganizationDto.ContactPoint("email", "ward@example.test", null, null)),
                new OrganizationDto.Address("work", "physical", "Synthetic street 2", List.of("Synthetic street 2"),
                        "Synthetic City", "District", "State", "22222", "SE"),
                wardPhysicalType(), fullPosition(),
                new SubjectReference("Organization/org-1", null, "Synthetic Clinic", null),
                new SubjectReference("Location/parent-loc", null, "Synthetic Building", null)));
        // Exact decimals including scale.
        assertThat(dto.position().longitude().scale()).isEqualTo(7);
        assertThat(dto.position().altitude().toPlainString()).isEqualTo("28.50");
        // hoursOfOperation, availabilityExceptions, endpoint and extensions are not part of the projection.
        assertThat(Arrays.stream(LocationDto.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("id", "versionId", "identifiers", "status", "operationalStatus", "name", "aliases",
                        "description", "mode", "types", "telecoms", "address", "physicalType", "position",
                        "managingOrganization", "partOf");
    }

    @Test
    void emptyLocationIsValidAndOptionalFieldsStayNullOrEmpty() {
        assertThat(mapper.toDto(parse("{\"resourceType\":\"Location\",\"id\":\"l2\"}"))).isEqualTo(new LocationDto(
                "l2", null, List.of(), null, null, null, List.of(), null, null, List.of(), List.of(), null, null, null,
                null, null));
    }

    @Test
    void readPreservesAbsoluteAndForeignReferencesVerbatim() {
        LocationDto dto = mapper.toDto(parse("""
                {"resourceType":"Location","id":"l3",
                 "managingOrganization":{"reference":"https://other.example.test/fhir/Organization/9","type":"Organization"},
                 "partOf":{"identifier":{"system":"urn:synthetic:location","value":"P-1"}}}
                """));
        assertThat(dto.managingOrganization()).isEqualTo(new SubjectReference(
                "https://other.example.test/fhir/Organization/9", "Organization", null, null));
        assertThat(dto.partOf()).isEqualTo(new SubjectReference(null, null, null,
                new Identifier("urn:synthetic:location", "P-1")));
    }

    @Test
    void readRejectsNullAndIncompletePositionsButKeepsStoredCoordinatesExactly() {
        assertThatThrownBy(() -> mapper.toDto(null)).isInstanceOf(IllegalArgumentException.class);
        for (String position : new String[] {"{\"longitude\":18.0}", "{\"latitude\":59.0}", "{\"altitude\":1}"}) {
            assertThatThrownBy(() -> mapper.toDto(parse("{\"resourceType\":\"Location\",\"id\":\"l4\",\"position\":" + position + "}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Location position requires longitude and latitude");
        }
        // A read projection does not reinterpret stored data.
        assertThat(mapper.toDto(parse("{\"resourceType\":\"Location\",\"id\":\"l5\",\"position\":{\"longitude\":200.5,\"latitude\":-95}}"))
                .position()).isEqualTo(new Position(new BigDecimal("200.5"), new BigDecimal("-95"), null));
    }

    @Test
    void writeRequestMapsToExactR4JsonWithReferencesAndExactCoordinates() {
        var request = new LocationWriteRequest(List.of(new Identifier("urn:synthetic:location", "LOC-1")), Status.ACTIVE,
                "Synthetic Ward", List.of("Ward A"), "Synthetic ward description", Mode.INSTANCE, List.of(hospitalType()),
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000002", ContactPointUse.HOME, 1)),
                new Address(AddressUse.HOME, AddressType.BOTH, null, List.of("Synthetic street 2"), "Synthetic City",
                        null, null, "22222", "SE"),
                wardPhysicalType(), fullPosition(), "org-1", "parent-loc");

        assertThat(encode(mapper.toResource(request))).isEqualTo("{\"resourceType\":\"Location\","
                + "\"identifier\":[{\"system\":\"urn:synthetic:location\",\"value\":\"LOC-1\"}],\"status\":\"active\","
                + "\"name\":\"Synthetic Ward\",\"alias\":[\"Ward A\"],\"description\":\"Synthetic ward description\","
                + "\"mode\":\"instance\","
                + "\"type\":[{\"coding\":[{\"system\":\"" + TYPE_SYSTEM + "\",\"code\":\"HOSP\",\"display\":\"Hospital\"}],\"text\":\"Hospital\"}],"
                + "\"telecom\":[{\"system\":\"phone\",\"value\":\"+46000000002\",\"use\":\"home\",\"rank\":1}],"
                + "\"address\":{\"use\":\"home\",\"type\":\"both\",\"line\":[\"Synthetic street 2\"],\"city\":\"Synthetic City\","
                + "\"postalCode\":\"22222\",\"country\":\"SE\"},"
                + "\"physicalType\":{\"coding\":[{\"system\":\"" + PHYSICAL_SYSTEM + "\",\"code\":\"wa\",\"display\":\"Ward\"}],\"text\":\"Ward\"},"
                + "\"position\":{\"longitude\":18.0686000,\"latitude\":59.3293000,\"altitude\":28.50},"
                + "\"managingOrganization\":{\"reference\":\"Organization/org-1\"},"
                + "\"partOf\":{\"reference\":\"Location/parent-loc\"}}");
    }

    @Test
    void emptyAndReferenceOnlyRequestsInventNothing() {
        assertThat(encode(mapper.toResource(empty()))).isEqualTo("{\"resourceType\":\"Location\"}");
        assertThat(encode(mapper.toResource(new LocationWriteRequest(null, null, null, null, null, null, null, null,
                null, null, null, "org-1", null))))
                .isEqualTo("{\"resourceType\":\"Location\",\"managingOrganization\":{\"reference\":\"Organization/org-1\"}}");
        assertThat(encode(mapper.toResource(new LocationWriteRequest(null, Status.SUSPENDED, null, null, null, Mode.KIND,
                null, null, null, null, null, null, "parent.loc-2"))))
                .isEqualTo("{\"resourceType\":\"Location\",\"status\":\"suspended\",\"mode\":\"kind\","
                        + "\"partOf\":{\"reference\":\"Location/parent.loc-2\"}}");
    }

    @Test
    void acceptsCoordinateBoundariesAndKeepsHighPrecision() {
        for (String[] valid : new String[][] {{"180", "90", null}, {"-180", "-90", "-430.5"}, {"0", "0", "0"},
                {"-180.000", "90.0000", null}}) {
            var position = mapper.toResource(at(valid[0], valid[1], valid[2])).getPosition();
            assertThat(position.getLongitude()).isEqualTo(new BigDecimal(valid[0]));
            assertThat(position.getLatitude()).isEqualTo(new BigDecimal(valid[1]));
        }
        assertThat(encode(mapper.toResource(at("18.068600000000000000001", "59.329300000000000000001", "0.000"))))
                .contains("\"position\":{\"longitude\":18.068600000000000000001,\"latitude\":59.329300000000000000001,"
                        + "\"altitude\":0.000}");
    }

    static Stream<Arguments> invalidRequests() {
        String uri = "urn:synthetic:location";
        Coding valid = new Coding(TYPE_SYSTEM, null, "HOSP", null, null);
        return Stream.of(
                Arguments.of(Named.of("null request", null)),
                Arguments.of(Named.of("empty name", named(""))),
                Arguments.of(Named.of("blank name", named("  "))),
                Arguments.of(Named.of("blank description", described(" "))),
                Arguments.of(Named.of("null identifier", withIdentifiers((Identifier) null))),
                Arguments.of(Named.of("identifier without value", withIdentifiers(new Identifier(uri, null)))),
                Arguments.of(Named.of("relative identifier system", withIdentifiers(new Identifier("location-system", "1")))),
                Arguments.of(Named.of("duplicate identifier", withIdentifiers(new Identifier(uri, "1"), new Identifier(uri, "1")))),
                Arguments.of(Named.of("null alias", withAliases((String) null))),
                Arguments.of(Named.of("blank alias", withAliases(" "))),
                Arguments.of(Named.of("duplicate alias", withAliases("A", "A"))),
                Arguments.of(Named.of("null type", withType(null))),
                Arguments.of(Named.of("empty type", withType(new Concept(null, List.of())))),
                Arguments.of(Named.of("blank type text", withType(new Concept(" ", List.of(valid))))),
                Arguments.of(Named.of("relative coding system", withType(new Concept(null, List.of(new Coding("v3-RoleCode", null, "HOSP", null, null)))))),
                Arguments.of(Named.of("empty physical type", withPhysicalType(new Concept(null, List.of())))),
                Arguments.of(Named.of("physical type coding without code", withPhysicalType(new Concept(null, List.of(new Coding(PHYSICAL_SYSTEM, null, "", null, null)))))),
                Arguments.of(Named.of("null telecom", withTelecom(null))),
                Arguments.of(Named.of("telecom without system (cpt-2)", withTelecom(new ContactPoint(null, "+46000000002", null, null)))),
                Arguments.of(Named.of("blank telecom value", withTelecom(new ContactPoint(ContactPointSystem.PHONE, " ", null, null)))),
                Arguments.of(Named.of("telecom rank zero", withTelecom(new ContactPoint(ContactPointSystem.PHONE, "1", null, 0)))),
                Arguments.of(Named.of("address without content", withAddress(new Address(AddressUse.WORK, AddressType.BOTH, null, null, null, null, null, null, null)))),
                Arguments.of(Named.of("null address line", withAddress(address(null, Arrays.asList((String) null), null)))),
                Arguments.of(Named.of("blank address line", withAddress(address(null, List.of(" "), null)))),
                Arguments.of(Named.of("blank city", withAddress(address(null, List.of("Line"), "")))),
                Arguments.of(Named.of("position without longitude", at(null, "59.3", null))),
                Arguments.of(Named.of("position without latitude", at("18.0", null, null))),
                Arguments.of(Named.of("position without coordinates", at(null, null, "10"))),
                Arguments.of(Named.of("longitude above 180", at("180.0000001", "0", null))),
                Arguments.of(Named.of("longitude below -180", at("-181", "0", null))),
                Arguments.of(Named.of("latitude above 90", at("0", "90.1", null))),
                Arguments.of(Named.of("latitude below -90", at("0", "-90.5", null))),
                Arguments.of(Named.of("managingOrganization reference URL", references("Organization/org-1", null))),
                Arguments.of(Named.of("managingOrganization absolute URL", references("https://example.test/Organization/1", null))),
                Arguments.of(Named.of("empty managingOrganization", references("", null))),
                Arguments.of(Named.of("managingOrganization with underscore", references("org_1", null))),
                Arguments.of(Named.of("managingOrganization too long", references("x".repeat(65), null))),
                Arguments.of(Named.of("partOf reference URL", references(null, "Location/parent"))),
                Arguments.of(Named.of("partOf traversal", references(null, "../parent"))),
                Arguments.of(Named.of("partOf with query", references(null, "parent?x=1"))),
                Arguments.of(Named.of("blank partOf", references(null, " "))));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidWriteRequests(LocationWriteRequest request) {
        assertThatThrownBy(() -> mapper.toResource(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Location write request");
    }

    @Test
    void locationCodesAreTheCompleteR4ValueSets() {
        assertThat(Arrays.stream(Status.values()).map(Status::code)).containsExactly("active", "suspended", "inactive");
        assertThat(Arrays.stream(Mode.values()).map(Mode::code)).containsExactly("instance", "kind");
        assertThat(Arrays.stream(ContactPointUse.values()).map(ContactPointUse::code)).containsExactly("home", "work", "temp", "old", "mobile");
        assertThat(Arrays.stream(AddressUse.values()).map(AddressUse::code)).containsExactly("home", "work", "temp", "old", "billing");
        for (var value : Status.values()) {
            assertThat(Location.LocationStatus.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
        for (var value : Mode.values()) {
            assertThat(Location.LocationMode.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
        for (var value : ContactPointSystem.values()) {
            assertThat(org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
        for (var value : ContactPointUse.values()) {
            assertThat(org.hl7.fhir.r4.model.ContactPoint.ContactPointUse.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
        for (var value : AddressUse.values()) {
            assertThat(org.hl7.fhir.r4.model.Address.AddressUse.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
        for (var value : AddressType.values()) {
            assertThat(org.hl7.fhir.r4.model.Address.AddressType.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
    }

    @Test
    void mergeReplacesManagedFieldsAndPreservesEveryUnmanagedElement() {
        Location current = parse(FULL);
        Location desired = mapper.toResource(new LocationWriteRequest(List.of(new Identifier(null, "LOC-2")),
                Status.INACTIVE, "Renamed Ward", null, null, null, null, null, null, null, null, "org-2", null));
        String currentBefore = encode(current);
        String desiredBefore = encode(desired);

        Location merged = mapper.mergeForUpdate(current, desired, "loc-1");

        // Unversioned: the encoded body carries no meta.versionId; the client sends the version as If-Match.
        assertThat(merged.getIdElement().getValue()).isEqualTo("Location/loc-1");
        assertThat(encode(merged)).doesNotContain("versionId", "lastUpdated");
        assertThat(merged.getMeta().getSource()).isEqualTo("#synthetic-source");
        assertThat(merged.getMeta().getProfile()).extracting(profile -> profile.getValue()).containsExactly("urn:synthetic:profile");
        assertThat(merged.getMeta().getTagFirstRep().getCode()).isEqualTo("t");
        assertThat(merged.getMeta().getSecurityFirstRep().getCode()).isEqualTo("s");
        // Unmanaged elements preserved, including the read-only operational status.
        assertThat(merged.getLanguage()).isEqualTo("sv");
        assertThat(merged.getText().getStatus().toCode()).isEqualTo("additional");
        assertThat(merged.getContained()).hasSize(1);
        assertThat(merged.getExtensionByUrl("urn:synthetic:extension")).isNotNull();
        assertThat(merged.getOperationalStatus().getCode()).isEqualTo("O");
        assertThat(merged.getHoursOfOperationFirstRep().getOpeningTime()).isEqualTo("08:00:00");
        assertThat(merged.getAvailabilityExceptions()).isEqualTo("Synthetic availability exceptions");
        assertThat(merged.getEndpointFirstRep().getReference()).isEqualTo("Endpoint/ep-1");
        // Managed elements are exactly the desired state: absent values remove the element.
        assertThat(merged.getIdentifier()).singleElement().satisfies(identifier -> {
            assertThat(identifier.getValue()).isEqualTo("LOC-2");
            assertThat(identifier.hasSystem() || identifier.hasType() || identifier.hasUse()).isFalse();
        });
        assertThat(merged.getStatus().toCode()).isEqualTo("inactive");
        assertThat(merged.getStatusElement().hasExtension()).isFalse();
        assertThat(merged.getName()).isEqualTo("Renamed Ward");
        assertThat(merged.getManagingOrganization().getReference()).isEqualTo("Organization/org-2");
        assertThat(merged.getManagingOrganization().hasDisplay()).isFalse();
        assertThat(merged.hasAlias() || merged.hasDescription() || merged.hasMode() || merged.hasType()
                || merged.hasTelecom() || merged.hasAddress() || merged.hasPhysicalType() || merged.hasPosition()
                || merged.hasPartOf()).isFalse();
        // Neither input changed.
        assertThat(encode(current)).isEqualTo(currentBefore);
        assertThat(encode(desired)).isEqualTo(desiredBefore);
    }

    @Test
    void unchangedEntriesKeepHiddenSubfieldsWhileChangedEntriesAreReplaced() {
        Location current = parse(FULL);
        var unchanged = unchangedFullRequest();
        var request = new LocationWriteRequest(unchanged.identifiers(), Status.ACTIVE, unchanged.name(),
                List.of("Ward B", "Ward A"), unchanged.description(), Mode.INSTANCE, unchanged.types(),
                List.of(unchanged.telecoms().get(0),
                        new ContactPoint(ContactPointSystem.EMAIL, "new-ward@example.test", ContactPointUse.WORK, 2)),
                unchanged.address(), unchanged.physicalType(), unchanged.position(), "org-1", "parent-loc");

        Location merged = mapper.mergeForUpdate(current, mapper.toResource(request), "loc-1");

        var identifier = merged.getIdentifierFirstRep();
        assertThat(identifier.getUse().toCode()).isEqualTo("official");
        assertThat(identifier.getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
        assertThat(merged.getStatusElement().getExtensionByUrl("urn:synthetic:status")).isNotNull();
        assertThat(merged.getTypeFirstRep().getCodingFirstRep().getExtensionByUrl("urn:synthetic:coding")).isNotNull();
        assertThat(merged.getAlias()).extracting(alias -> alias.getValue()).containsExactly("Ward B", "Ward A");
        assertThat(merged.getTelecom().get(0).getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
        assertThat(merged.getTelecom().get(1).getValue()).isEqualTo("new-ward@example.test");
        assertThat(merged.getAddress().getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
        assertThat(merged.getManagingOrganization().getDisplay()).isEqualTo("Synthetic Clinic");
        assertThat(merged.getPartOf().getDisplay()).isEqualTo("Synthetic Building");
        assertThat(merged.getPosition().getLongitudeElement().getValueAsString()).isEqualTo("18.0686000");

        // Changed single elements and references are replaced, without the old hidden subfields.
        var changed = new LocationWriteRequest(unchanged.identifiers(), null, null, null, null, null, null, null,
                new Address(AddressUse.WORK, AddressType.PHYSICAL, "Moved street 3", List.of("Moved street 3"),
                        "Synthetic City", null, null, "33333", "SE"),
                null, new Position(new BigDecimal("18.07"), new BigDecimal("59.33"), null), "org-1", "other-parent");
        Location replaced = mapper.mergeForUpdate(current, mapper.toResource(changed), "loc-1");
        assertThat(replaced.getAddress().hasPeriod()).isFalse();
        assertThat(replaced.getAddress().getText()).isEqualTo("Moved street 3");
        assertThat(replaced.getPosition().getLongitude()).isEqualTo(new BigDecimal("18.07"));
        assertThat(replaced.getPosition().hasAltitude()).isFalse();
        assertThat(replaced.getPartOf().getReference()).isEqualTo("Location/other-parent");
        assertThat(replaced.getPartOf().hasDisplay()).isFalse();
        assertThat(replaced.getManagingOrganization().getDisplay()).isEqualTo("Synthetic Clinic");
        assertThat(replaced.hasStatus() || replaced.hasPhysicalType()).isFalse();
    }

    @Test
    void unchangedRequestKeepsEveryManagedElementDeeplyEqual() {
        Location current = parse(FULL);
        Location merged = mapper.mergeForUpdate(current, mapper.toResource(unchangedFullRequest()), "loc-1");

        assertThat(merged.getIdentifierFirstRep().equalsDeep(current.getIdentifierFirstRep())).isTrue();
        assertThat(merged.getStatusElement().equalsDeep(current.getStatusElement())).isTrue();
        assertThat(merged.getTypeFirstRep().equalsDeep(current.getTypeFirstRep())).isTrue();
        assertThat(merged.getTelecom().get(0).equalsDeep(current.getTelecom().get(0))).isTrue();
        assertThat(merged.getAddress().equalsDeep(current.getAddress())).isTrue();
        assertThat(merged.getPhysicalType().equalsDeep(current.getPhysicalType())).isTrue();
        assertThat(merged.getPosition().equalsDeep(current.getPosition())).isTrue();
        assertThat(merged.getManagingOrganization().equalsDeep(current.getManagingOrganization())).isTrue();
        assertThat(merged.getPartOf().equalsDeep(current.getPartOf())).isTrue();
        LocationDto before = mapper.toDto(current);
        LocationDto after = mapper.toDto(merged);
        // The merged resource has no server version yet; otherwise the projection is unchanged.
        assertThat(after.versionId()).isNull();
        assertThat(after).usingRecursiveComparison().ignoringFields("versionId").isEqualTo(before);
    }

    @Test
    void generatedNarrativeIsRemovedAndOtherNarrativeIsKept() {
        String generated = FULL.replace("\"status\":\"additional\"", "\"status\":\"generated\"");
        String extensions = FULL.replace("\"status\":\"additional\"", "\"status\":\"extensions\"");
        var desired = mapper.toResource(named("Synthetic Ward"));

        assertThat(mapper.mergeForUpdate(parse(generated), desired, "loc-1").hasText()).isFalse();
        assertThat(mapper.mergeForUpdate(parse(extensions), desired, "loc-1").getText().getStatus().toCode())
                .isEqualTo("extensions");
    }

    @Test
    void metaHoldingOnlyServerFieldsIsDropped() {
        Location current = parse("""
                {"resourceType":"Location","id":"l6","meta":{"versionId":"7","lastUpdated":"2026-06-01T10:00:00Z"},"name":"N"}
                """);
        Location merged = mapper.mergeForUpdate(current, mapper.toResource(named("M")), "l6");
        assertThat(merged.hasMeta()).isFalse();
        assertThat(encode(merged)).isEqualTo("{\"resourceType\":\"Location\",\"id\":\"l6\",\"name\":\"M\"}");
    }

    @Test
    void unknownModifierSemanticsBlockUpdatesButUnmanagedModifiersArePreserved() {
        var desired = mapper.toResource(named("N"));
        String modifier = "\"modifierExtension\":[{\"url\":\"urn:synthetic:modifier\",\"valueBoolean\":true}],";
        Location implicitRules = parse(FULL.replace("\"language\":\"sv\"", "\"implicitRules\":\"urn:synthetic:rules\",\"language\":\"sv\""));
        Location resourceModifier = parse(FULL.replace("\"language\":\"sv\"", modifier + "\"language\":\"sv\""));
        Location positionModifier = parse(FULL.replace("\"position\":{", "\"position\":{" + modifier));
        Location hoursModifier = parse(FULL.replace("\"hoursOfOperation\":[{", "\"hoursOfOperation\":[{" + modifier));

        for (Location unsafe : List.of(implicitRules, resourceModifier, positionModifier)) {
            assertThat(mapper.isSafelyUpdatable(unsafe)).isFalse();
            assertThatThrownBy(() -> mapper.mergeForUpdate(unsafe, desired, "loc-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.isSafelyUpdatable(null)).isFalse();
        assertThat(mapper.isSafelyUpdatable(hoursModifier)).isTrue();
        assertThat(mapper.mergeForUpdate(hoursModifier, desired, "loc-1").getHoursOfOperationFirstRep()
                .getModifierExtensionFirstRep().getUrl()).isEqualTo("urn:synthetic:modifier");
    }

    @Test
    void mergeRejectsInvalidArguments() {
        Location current = parse(FULL);
        Location desired = mapper.toResource(named("N"));
        assertThatThrownBy(() -> mapper.mergeForUpdate(null, desired, "loc-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.mergeForUpdate(current, null, "loc-1")).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[] {null, "", "Location/loc-1", "a_b", "x".repeat(65)}) {
            assertThatThrownBy(() -> mapper.mergeForUpdate(current, desired, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
