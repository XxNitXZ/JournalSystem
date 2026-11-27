package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;
import com.medinexus.medicore.organization.dto.OrganizationDto;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.Address;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.AddressType;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.AddressUse;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPoint;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPointSystem;
import com.medinexus.medicore.organization.dto.OrganizationWriteRequest.ContactPointUse;

import ca.uhn.fhir.context.FhirContext;

/** Synthetic Organization data only; no HTTP. */
class OrganizationMapperTest {

    private static final FhirContext CONTEXT = FhirContext.forR4();
    private static final String TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/organization-type";

    /** Every managed field with hidden subfields, plus unmanaged elements that an update must preserve. */
    static final String FULL = """
            {"resourceType":"Organization","id":"org-1",
             "meta":{"versionId":"3","lastUpdated":"2026-06-01T10:00:00.000+00:00","source":"#synthetic-source",
               "profile":["urn:synthetic:profile"],"tag":[{"system":"urn:synthetic:tag","code":"t"}],
               "security":[{"system":"urn:synthetic:security","code":"s"}]},
             "language":"sv",
             "text":{"status":"additional","div":"<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Synthetic narrative</div>"},
             "contained":[{"resourceType":"Basic","id":"c1","code":{"text":"Synthetic contained"}}],
             "extension":[{"url":"urn:synthetic:extension","valueReference":{"reference":"#c1"}}],
             "identifier":[{"use":"official","type":{"text":"Synthetic identifier type"},"system":"urn:synthetic:org",
               "value":"ORG-1","period":{"start":"2020-01-01"}}],
             "active":false,
             "type":[{"coding":[{"extension":[{"url":"urn:synthetic:coding","valueString":"kept"}],
               "system":"http://terminology.hl7.org/CodeSystem/organization-type","code":"prov",
               "display":"Healthcare Provider"}],"text":"Provider"}],
             "name":"Synthetic Clinic",
             "_name":{"extension":[{"url":"urn:synthetic:name","valueString":"kept"}]},
             "alias":["Clinic A","Clinic B"],
             "telecom":[{"system":"phone","value":"+46000000000","use":"work","rank":1,"period":{"start":"2020-01-01"}},
               {"system":"email","value":"clinic@example.test"}],
             "address":[{"use":"work","type":"physical","text":"Synthetic street 1","line":["Synthetic street 1"],
               "city":"Synthetic City","district":"District","state":"State","postalCode":"11111","country":"SE",
               "period":{"start":"2020-01-01"}}],
             "partOf":{"reference":"Organization/parent-1","display":"Synthetic Parent"},
             "contact":[{"purpose":{"text":"ADMIN"},"name":{"text":"Synthetic Contact Person"},
               "telecom":[{"system":"phone","value":"+46000000001"}]}],
             "endpoint":[{"reference":"Endpoint/ep-1"}]}
            """;

    private final OrganizationMapper mapper = new OrganizationMapper();

    static Organization parse(String json) {
        return CONTEXT.newJsonParser().parseResource(Organization.class, json);
    }

    static String encode(Organization organization) {
        return CONTEXT.newJsonParser().encodeResourceToString(organization);
    }

    static Concept providerType() {
        return new Concept("Provider", List.of(new Coding(TYPE_SYSTEM, null, "prov", "Healthcare Provider", null)));
    }

    /** The write request that describes FULL's managed fields unchanged (as an edit of its DTO would). */
    static OrganizationWriteRequest unchangedFullRequest() {
        return new OrganizationWriteRequest(List.of(new Identifier("urn:synthetic:org", "ORG-1")), false,
                List.of(providerType()), "Synthetic Clinic", List.of("Clinic A", "Clinic B"),
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000000", ContactPointUse.WORK, 1),
                        new ContactPoint(ContactPointSystem.EMAIL, "clinic@example.test", null, null)),
                List.of(new Address(AddressUse.WORK, AddressType.PHYSICAL, "Synthetic street 1",
                        List.of("Synthetic street 1"), "Synthetic City", "District", "State", "11111", "SE")),
                "parent-1");
    }

    private static OrganizationWriteRequest org(List<Identifier> identifiers, String name, List<String> aliases,
            List<Concept> types, List<ContactPoint> telecoms, List<Address> addresses, String partOf) {
        return new OrganizationWriteRequest(identifiers, null, types, name, aliases, telecoms, addresses, partOf);
    }

    private static OrganizationWriteRequest named(String name) {
        return org(null, name, null, null, null, null, null);
    }

    private static OrganizationWriteRequest withIdentifiers(Identifier... identifiers) {
        return org(Arrays.asList(identifiers), "N", null, null, null, null, null);
    }

    private static OrganizationWriteRequest withAliases(String... aliases) {
        return org(null, "N", Arrays.asList(aliases), null, null, null, null);
    }

    private static OrganizationWriteRequest withType(Concept type) {
        return org(null, "N", null, Arrays.asList(type), null, null, null);
    }

    private static OrganizationWriteRequest withTelecom(ContactPoint telecom) {
        return org(null, "N", null, null, Arrays.asList(telecom), null, null);
    }

    private static OrganizationWriteRequest withAddress(Address address) {
        return org(null, "N", null, null, null, Arrays.asList(address), null);
    }

    private static Address address(String text, List<String> lines, String city) {
        return new Address(AddressUse.WORK, null, text, lines, city, null, null, null, null);
    }

    @Test
    void projectsEveryManagedFieldWithServerVersionAndVerbatimPartOf() {
        OrganizationDto dto = mapper.toDto(parse(FULL));

        assertThat(dto).isEqualTo(new OrganizationDto("org-1", "3", List.of(new Identifier("urn:synthetic:org", "ORG-1")),
                false, List.of(providerType()), "Synthetic Clinic", List.of("Clinic A", "Clinic B"),
                List.of(new OrganizationDto.ContactPoint("phone", "+46000000000", "work", 1),
                        new OrganizationDto.ContactPoint("email", "clinic@example.test", null, null)),
                List.of(new OrganizationDto.Address("work", "physical", "Synthetic street 1", List.of("Synthetic street 1"),
                        "Synthetic City", "District", "State", "11111", "SE")),
                new SubjectReference("Organization/parent-1", null, "Synthetic Parent", null)));
        // Contact persons, endpoints and extensions are not part of the projection.
        assertThat(Arrays.stream(OrganizationDto.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("id", "versionId", "identifiers", "active", "types", "name", "aliases", "telecoms",
                        "addresses", "partOf");
    }

    @Test
    void optionalFieldsStayNullOrEmptyWithoutInventedDefaults() {
        OrganizationDto named = mapper.toDto(parse("{\"resourceType\":\"Organization\",\"id\":\"o2\",\"name\":\"N\"}"));
        assertThat(named).isEqualTo(new OrganizationDto("o2", null, List.of(), null, List.of(), "N", List.of(),
                List.of(), List.of(), null));

        OrganizationDto identified = mapper.toDto(parse(
                "{\"resourceType\":\"Organization\",\"id\":\"o3\",\"identifier\":[{\"value\":\"X\"}],\"active\":true}"));
        assertThat(identified.name()).isNull();
        assertThat(identified.active()).isTrue();
        assertThat(identified.identifiers()).containsExactly(new Identifier(null, "X"));
    }

    @Test
    void readRejectsNullAndOrg1Violations() {
        assertThatThrownBy(() -> mapper.toDto(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.toDto(parse(
                "{\"resourceType\":\"Organization\",\"id\":\"o4\",\"alias\":[\"Only alias\"],\"active\":true}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Organization name or identifier is required");
    }

    @Test
    void writeRequestMapsToExactR4JsonWithoutIdOrMeta() {
        var request = new OrganizationWriteRequest(List.of(new Identifier("urn:synthetic:org", "ORG-1")), true,
                List.of(providerType()), "Synthetic Clinic", List.of("Clinic A"),
                List.of(new ContactPoint(ContactPointSystem.PHONE, "+46000000000", ContactPointUse.WORK, 1),
                        new ContactPoint(ContactPointSystem.EMAIL, "clinic@example.test", null, null)),
                List.of(new Address(AddressUse.WORK, AddressType.PHYSICAL, null, List.of("Synthetic street 1"),
                        "Synthetic City", null, null, "11111", "SE")),
                "parent-1");

        assertThat(encode(mapper.toResource(request))).isEqualTo("{\"resourceType\":\"Organization\","
                + "\"identifier\":[{\"system\":\"urn:synthetic:org\",\"value\":\"ORG-1\"}],\"active\":true,"
                + "\"type\":[{\"coding\":[{\"system\":\"" + TYPE_SYSTEM + "\",\"code\":\"prov\",\"display\":\"Healthcare Provider\"}],"
                + "\"text\":\"Provider\"}],\"name\":\"Synthetic Clinic\",\"alias\":[\"Clinic A\"],"
                + "\"telecom\":[{\"system\":\"phone\",\"value\":\"+46000000000\",\"use\":\"work\",\"rank\":1},"
                + "{\"system\":\"email\",\"value\":\"clinic@example.test\"}],"
                + "\"address\":[{\"use\":\"work\",\"type\":\"physical\",\"line\":[\"Synthetic street 1\"],"
                + "\"city\":\"Synthetic City\",\"postalCode\":\"11111\",\"country\":\"SE\"}],"
                + "\"partOf\":{\"reference\":\"Organization/parent-1\"}}");
    }

    @Test
    void minimalRequestsSatisfyOrg1WithoutInventedFields() {
        assertThat(encode(mapper.toResource(named("Synthetic Clinic"))))
                .isEqualTo("{\"resourceType\":\"Organization\",\"name\":\"Synthetic Clinic\"}");
        assertThat(encode(mapper.toResource(org(List.of(new Identifier(null, "ORG-9")), null, null, null, null, null, null))))
                .isEqualTo("{\"resourceType\":\"Organization\",\"identifier\":[{\"value\":\"ORG-9\"}]}");
        assertThat(encode(mapper.toResource(new OrganizationWriteRequest(null, false, null, "N", null, null, null, null))))
                .isEqualTo("{\"resourceType\":\"Organization\",\"active\":false,\"name\":\"N\"}");
    }

    static Stream<Arguments> invalidRequests() {
        String uri = "urn:synthetic:org";
        Coding valid = new Coding(TYPE_SYSTEM, null, "prov", null, null);
        return Stream.of(
                Arguments.of(Named.of("null request", null)),
                Arguments.of(Named.of("org-1: neither name nor identifier", org(null, null, List.of("Alias"), null, null, null, null))),
                Arguments.of(Named.of("empty name", named(""))),
                Arguments.of(Named.of("blank name", named("  "))),
                Arguments.of(Named.of("null identifier", withIdentifiers((Identifier) null))),
                Arguments.of(Named.of("identifier without value", withIdentifiers(new Identifier(uri, null)))),
                Arguments.of(Named.of("blank identifier value", withIdentifiers(new Identifier(uri, " ")))),
                Arguments.of(Named.of("relative identifier system", withIdentifiers(new Identifier("org-system", "1")))),
                Arguments.of(Named.of("blank identifier system", withIdentifiers(new Identifier(" ", "1")))),
                Arguments.of(Named.of("duplicate identifier", withIdentifiers(new Identifier(uri, "1"), new Identifier(uri, "1")))),
                Arguments.of(Named.of("null alias", withAliases((String) null))),
                Arguments.of(Named.of("blank alias", withAliases(" "))),
                Arguments.of(Named.of("duplicate alias", withAliases("A", "A"))),
                Arguments.of(Named.of("null type", withType(null))),
                Arguments.of(Named.of("empty type", withType(new Concept(null, List.of())))),
                Arguments.of(Named.of("blank type text", withType(new Concept(" ", List.of(valid))))),
                Arguments.of(Named.of("relative coding system", withType(new Concept(null, List.of(new Coding("organization-type", null, "prov", null, null)))))),
                Arguments.of(Named.of("coding without code", withType(new Concept(null, List.of(new Coding(TYPE_SYSTEM, null, " ", null, null)))))),
                Arguments.of(Named.of("null telecom", withTelecom(null))),
                Arguments.of(Named.of("telecom without system (cpt-2)", withTelecom(new ContactPoint(null, "+46000000000", null, null)))),
                Arguments.of(Named.of("telecom without value", withTelecom(new ContactPoint(ContactPointSystem.PHONE, null, null, null)))),
                Arguments.of(Named.of("blank telecom value", withTelecom(new ContactPoint(ContactPointSystem.PHONE, " ", null, null)))),
                Arguments.of(Named.of("telecom rank zero", withTelecom(new ContactPoint(ContactPointSystem.PHONE, "1", null, 0)))),
                Arguments.of(Named.of("negative telecom rank", withTelecom(new ContactPoint(ContactPointSystem.PHONE, "1", null, -1)))),
                Arguments.of(Named.of("null address", withAddress(null))),
                Arguments.of(Named.of("address without content", withAddress(new Address(AddressUse.WORK, AddressType.BOTH, null, null, null, null, null, null, null)))),
                Arguments.of(Named.of("null address line", withAddress(address(null, Arrays.asList((String) null), null)))),
                Arguments.of(Named.of("blank address line", withAddress(address(null, List.of(" "), null)))),
                Arguments.of(Named.of("blank address text", withAddress(address(" ", List.of("Line"), null)))),
                Arguments.of(Named.of("blank city", withAddress(address(null, List.of("Line"), "")))),
                Arguments.of(Named.of("partOf reference URL", org(null, "N", null, null, null, null, "Organization/1"))),
                Arguments.of(Named.of("partOf absolute URL", org(null, "N", null, null, null, null, "https://example.test/Organization/1"))),
                Arguments.of(Named.of("partOf traversal", org(null, "N", null, null, null, null, "../x"))),
                Arguments.of(Named.of("empty partOf", org(null, "N", null, null, null, null, ""))),
                Arguments.of(Named.of("partOf with underscore", org(null, "N", null, null, null, null, "a_b"))),
                Arguments.of(Named.of("partOf too long", org(null, "N", null, null, null, null, "x".repeat(65)))));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidWriteRequests(OrganizationWriteRequest request) {
        assertThatThrownBy(() -> mapper.toResource(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Organization write request");
    }

    @Test
    void organizationCodesAreValidR4CodesAndExcludeHome() {
        assertThat(Arrays.stream(ContactPointUse.values()).map(ContactPointUse::code)).containsExactly("work", "temp", "old", "mobile");
        assertThat(Arrays.stream(AddressUse.values()).map(AddressUse::code)).containsExactly("work", "temp", "old", "billing");
        assertThat(Arrays.stream(ContactPointSystem.values()).map(ContactPointSystem::code))
                .containsExactly("phone", "fax", "email", "pager", "url", "sms", "other");
        assertThat(Arrays.stream(AddressType.values()).map(AddressType::code)).containsExactly("postal", "physical", "both");
        for (var value : ContactPointUse.values()) {
            assertThat(org.hl7.fhir.r4.model.ContactPoint.ContactPointUse.fromCode(value.code()).toCode()).isEqualTo(value.code());
        }
        for (var value : ContactPointSystem.values()) {
            assertThat(org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem.fromCode(value.code()).toCode()).isEqualTo(value.code());
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
        Organization current = parse(FULL);
        Organization desired = mapper.toResource(new OrganizationWriteRequest(List.of(new Identifier(null, "ORG-2")), true,
                null, "Renamed Clinic", null, null, null, null));
        String currentBefore = encode(current);
        String desiredBefore = encode(desired);

        Organization merged = mapper.mergeForUpdate(current, desired, "org-1");

        // Unversioned: the encoded body carries no meta.versionId; the client sends the version as If-Match.
        assertThat(merged.getIdElement().getValue()).isEqualTo("Organization/org-1");
        assertThat(encode(merged)).doesNotContain("versionId", "lastUpdated");
        // Server-assigned metadata removed; everything else in meta preserved.
        assertThat(merged.getMeta().hasVersionId()).isFalse();
        assertThat(merged.getMeta().hasLastUpdated()).isFalse();
        assertThat(merged.getMeta().getSource()).isEqualTo("#synthetic-source");
        assertThat(merged.getMeta().getProfile()).extracting(profile -> profile.getValue()).containsExactly("urn:synthetic:profile");
        assertThat(merged.getMeta().getTagFirstRep().getCode()).isEqualTo("t");
        assertThat(merged.getMeta().getSecurityFirstRep().getCode()).isEqualTo("s");
        // Unmanaged elements preserved.
        assertThat(merged.getLanguage()).isEqualTo("sv");
        assertThat(merged.getText().getStatus().toCode()).isEqualTo("additional");
        assertThat(merged.getContained()).hasSize(1);
        assertThat(merged.getExtensionByUrl("urn:synthetic:extension")).isNotNull();
        assertThat(merged.getContactFirstRep().getName().getText()).isEqualTo("Synthetic Contact Person");
        assertThat(merged.getContactFirstRep().getTelecomFirstRep().getValue()).isEqualTo("+46000000001");
        assertThat(merged.getEndpointFirstRep().getReference()).isEqualTo("Endpoint/ep-1");
        // Managed elements are exactly the desired state: absent values remove the element.
        assertThat(merged.getIdentifier()).singleElement().satisfies(identifier -> {
            assertThat(identifier.getValue()).isEqualTo("ORG-2");
            assertThat(identifier.hasSystem() || identifier.hasType() || identifier.hasUse()).isFalse();
        });
        assertThat(merged.getActive()).isTrue();
        assertThat(merged.getName()).isEqualTo("Renamed Clinic");
        assertThat(merged.getNameElement().hasExtension()).isFalse();
        assertThat(merged.hasType() || merged.hasAlias() || merged.hasTelecom() || merged.hasAddress() || merged.hasPartOf()).isFalse();
        // Neither input changed.
        assertThat(encode(current)).isEqualTo(currentBefore);
        assertThat(encode(desired)).isEqualTo(desiredBefore);
    }

    @Test
    void unchangedEntriesKeepHiddenSubfieldsWhileChangedEntriesAreReplaced() {
        Organization current = parse(FULL);
        var unchanged = unchangedFullRequest();
        var request = new OrganizationWriteRequest(unchanged.identifiers(), false, unchanged.types(), unchanged.name(),
                List.of("Clinic B", "Clinic A"),
                List.of(unchanged.telecoms().get(0),
                        new ContactPoint(ContactPointSystem.EMAIL, "new@example.test", ContactPointUse.WORK, 2)),
                unchanged.addresses(), "parent-1");

        Organization merged = mapper.mergeForUpdate(current, mapper.toResource(request), "org-1");

        var identifier = merged.getIdentifierFirstRep();
        assertThat(identifier.getUse().toCode()).isEqualTo("official");
        assertThat(identifier.getType().getText()).isEqualTo("Synthetic identifier type");
        assertThat(identifier.getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
        assertThat(merged.getTypeFirstRep().getCodingFirstRep().getExtensionByUrl("urn:synthetic:coding")).isNotNull();
        assertThat(merged.getNameElement().getExtensionByUrl("urn:synthetic:name")).isNotNull();
        assertThat(merged.getActiveElement().getValue()).isFalse();
        assertThat(merged.getAlias()).extracting(alias -> alias.getValue()).containsExactly("Clinic B", "Clinic A");
        assertThat(merged.getTelecom()).hasSize(2);
        assertThat(merged.getTelecom().get(0).getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
        assertThat(merged.getTelecom().get(1).getValue()).isEqualTo("new@example.test");
        assertThat(merged.getTelecom().get(1).getUse().toCode()).isEqualTo("work");
        assertThat(merged.getTelecom().get(1).getRank()).isEqualTo(2);
        assertThat(merged.getAddressFirstRep().getPeriod().getStartElement().getValueAsString()).isEqualTo("2020-01-01");
        assertThat(merged.getPartOf().getDisplay()).isEqualTo("Synthetic Parent");

        // Changing an entry's visible values replaces it, so its hidden subfields are not carried over.
        var changed = new OrganizationWriteRequest(List.of(new Identifier("urn:synthetic:org", "ORG-1-NEW")), null,
                null, "Synthetic Clinic", null, null, null, "parent-2");
        Organization replaced = mapper.mergeForUpdate(current, mapper.toResource(changed), "org-1");
        assertThat(replaced.getIdentifierFirstRep().hasType() || replaced.getIdentifierFirstRep().hasPeriod()).isFalse();
        assertThat(replaced.getPartOf().getReference()).isEqualTo("Organization/parent-2");
        assertThat(replaced.getPartOf().hasDisplay()).isFalse();
        assertThat(replaced.hasActive()).isFalse();
    }

    @Test
    void unchangedRequestKeepsEveryManagedElementDeeplyEqual() {
        Organization current = parse(FULL);
        Organization merged = mapper.mergeForUpdate(current, mapper.toResource(unchangedFullRequest()), "org-1");

        assertThat(merged.getIdentifierFirstRep().equalsDeep(current.getIdentifierFirstRep())).isTrue();
        assertThat(merged.getTypeFirstRep().equalsDeep(current.getTypeFirstRep())).isTrue();
        assertThat(merged.getNameElement().equalsDeep(current.getNameElement())).isTrue();
        assertThat(merged.getTelecom().get(0).equalsDeep(current.getTelecom().get(0))).isTrue();
        assertThat(merged.getTelecom().get(1).equalsDeep(current.getTelecom().get(1))).isTrue();
        assertThat(merged.getAddressFirstRep().equalsDeep(current.getAddressFirstRep())).isTrue();
        assertThat(merged.getPartOf().equalsDeep(current.getPartOf())).isTrue();
        // The merged resource has no server version yet; otherwise the projection is unchanged.
        assertThat(mapper.toDto(merged)).isEqualTo(new OrganizationDto("org-1", null, mapper.toDto(current).identifiers(),
                false, List.of(providerType()), "Synthetic Clinic", List.of("Clinic A", "Clinic B"),
                mapper.toDto(current).telecoms(), mapper.toDto(current).addresses(), mapper.toDto(current).partOf()));
    }

    @Test
    void generatedNarrativeIsRemovedAndOtherNarrativeIsKept() {
        String generated = FULL.replace("\"status\":\"additional\"", "\"status\":\"generated\"");
        String extensions = FULL.replace("\"status\":\"additional\"", "\"status\":\"extensions\"");
        var desired = mapper.toResource(named("Synthetic Clinic"));

        assertThat(mapper.mergeForUpdate(parse(generated), desired, "org-1").hasText()).isFalse();
        assertThat(mapper.mergeForUpdate(parse(extensions), desired, "org-1").getText().getStatus().toCode())
                .isEqualTo("extensions");
    }

    @Test
    void metaHoldingOnlyServerFieldsIsDropped() {
        Organization current = parse("""
                {"resourceType":"Organization","id":"o5","meta":{"versionId":"7","lastUpdated":"2026-06-01T10:00:00Z"},"name":"N"}
                """);
        Organization merged = mapper.mergeForUpdate(current, mapper.toResource(named("M")), "o5");
        assertThat(merged.hasMeta()).isFalse();
        assertThat(encode(merged)).isEqualTo("{\"resourceType\":\"Organization\",\"id\":\"o5\",\"name\":\"M\"}");
    }

    @Test
    void resourceLevelModifierSemanticsBlockUpdatesButContactModifiersArePreserved() {
        var desired = mapper.toResource(named("N"));
        Organization implicitRules = parse(FULL.replace("\"language\":\"sv\"", "\"implicitRules\":\"urn:synthetic:rules\",\"language\":\"sv\""));
        Organization modifier = parse(FULL.replace("\"language\":\"sv\"",
                "\"modifierExtension\":[{\"url\":\"urn:synthetic:modifier\",\"valueBoolean\":true}],\"language\":\"sv\""));
        Organization contactModifier = parse(FULL.replace("\"purpose\":{\"text\":\"ADMIN\"}",
                "\"modifierExtension\":[{\"url\":\"urn:synthetic:contact-modifier\",\"valueBoolean\":true}],\"purpose\":{\"text\":\"ADMIN\"}"));

        for (Organization unsafe : List.of(implicitRules, modifier)) {
            assertThat(mapper.isSafelyUpdatable(unsafe)).isFalse();
            assertThatThrownBy(() -> mapper.mergeForUpdate(unsafe, desired, "org-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.isSafelyUpdatable(null)).isFalse();
        assertThat(mapper.isSafelyUpdatable(contactModifier)).isTrue();
        assertThat(mapper.mergeForUpdate(contactModifier, desired, "org-1").getContactFirstRep()
                .getModifierExtensionFirstRep().getUrl()).isEqualTo("urn:synthetic:contact-modifier");
    }

    @Test
    void mergeRejectsInvalidArguments() {
        Organization current = parse(FULL);
        Organization desired = mapper.toResource(named("N"));
        assertThatThrownBy(() -> mapper.mergeForUpdate(null, desired, "org-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.mergeForUpdate(current, null, "org-1")).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[] {null, "", "Organization/org-1", "a_b", "x".repeat(65)}) {
            assertThatThrownBy(() -> mapper.mergeForUpdate(current, desired, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
