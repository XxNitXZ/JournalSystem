package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterClass;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.EncounterLocation;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.LocationStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Participant;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.ParticipantType;
import com.medinexus.medicore.medicalrecord.dto.CreateEncounterRequest.Status;
import com.medinexus.medicore.medicalrecord.dto.EncounterDto;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Identifier;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.PeriodValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.QuantityValue;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.SubjectReference;

import ca.uhn.fhir.context.FhirContext;

class EncounterMapperTest {
    private static final FhirContext CONTEXT = FhirContext.forR4();
    private static final String CLASS = "\"class\":{\"system\":\"" + EncounterClass.SYSTEM + "\",\"code\":\"AMB\"}";
    private final EncounterMapper mapper = new EncounterMapper();

    private Encounter parse(String fields) {
        return CONTEXT.newJsonParser().parseResource(Encounter.class,
                "{\"resourceType\":\"Encounter\",\"status\":\"finished\"," + CLASS + fields + "}");
    }

    @Test
    void mapsProjectedFieldsAndPreservesEveryReferenceVerbatim() {
        Encounter encounter = parse("""
                ,"type":[{"text":"Synthetic visit","coding":[{"system":"urn:synthetic:type","version":"1","code":"checkup","display":"Check-up","userSelected":true}]},
                         {"text":"Second type"}],
                "serviceType":{"text":"Synthetic service"},
                "priority":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-ActPriority","code":"R"}]},
                "subject":{"reference":"Patient/synthetic-patient","type":"Patient","display":"Synthetic patient",
                           "identifier":{"system":"urn:synthetic","value":"p-1"}},
                "participant":[
                  {"type":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v3-ParticipationType","code":"ATND"}]}],
                   "period":{"start":"2026-06-15T09:00:00+02:00"},"individual":{"reference":"Practitioner/synthetic-practitioner"}},
                  {"individual":{"reference":"PractitionerRole/synthetic-role"}},
                  {"individual":{"reference":"RelatedPerson/synthetic-relative"}},
                  {"type":[{"text":"Unassigned role"}]}],
                "period":{"start":"2026-06-15T09:00:00+02:00","end":"2026-06-15T09:30:00.123+02:00"},
                "length":{"value":30,"unit":"min","system":"http://unitsofmeasure.org","code":"min"},
                "reasonCode":[{"text":"Synthetic reason"}],
                "reasonReference":[{"reference":"Condition/synthetic-condition"},{"reference":"Observation/synthetic-observation"}],
                "diagnosis":[{"condition":{"reference":"Condition/synthetic-condition"},"use":{"text":"Admission"},"rank":1}],
                "location":[{"location":{"reference":"Location/synthetic-room","display":"Room 1"},"status":"completed",
                             "physicalType":{"text":"Room"},"period":{"start":"2026-06"}}],
                "serviceProvider":{"reference":"Organization/synthetic-clinic"}
                """);
        encounter.setId("https://example.test/fhir/Encounter/synthetic-1/_history/4");

        EncounterDto dto = mapper.toDto(encounter);

        assertThat(dto.id()).isEqualTo("synthetic-1");
        assertThat(dto.status()).isEqualTo("finished");
        assertThat(dto.encounterClass()).isEqualTo(new Coding(EncounterClass.SYSTEM, null, "AMB", null, null));
        assertThat(dto.types()).containsExactly(
                new Concept("Synthetic visit", List.of(new Coding("urn:synthetic:type", "1", "checkup", "Check-up", true))),
                new Concept("Second type", List.of()));
        assertThat(dto.serviceType()).isEqualTo(new Concept("Synthetic service", List.of()));
        assertThat(dto.priority().coding()).extracting(Coding::code).containsExactly("R");
        assertThat(dto.subject()).isEqualTo(new SubjectReference("Patient/synthetic-patient", "Patient",
                "Synthetic patient", new Identifier("urn:synthetic", "p-1")));
        assertThat(dto.participants()).extracting(participant -> participant.individual() == null ? null
                : participant.individual().reference()).containsExactly("Practitioner/synthetic-practitioner",
                "PractitionerRole/synthetic-role", "RelatedPerson/synthetic-relative", null);
        assertThat(dto.participants().getFirst().types().getFirst().coding()).extracting(Coding::code).containsExactly("ATND");
        assertThat(dto.participants().getFirst().period()).isEqualTo(new PeriodValue("2026-06-15T09:00:00+02:00", null));
        assertThat(dto.participants().get(1).types()).isEmpty();
        assertThat(dto.participants().get(3).types()).containsExactly(new Concept("Unassigned role", List.of()));
        assertThat(dto.period()).isEqualTo(new PeriodValue("2026-06-15T09:00:00+02:00", "2026-06-15T09:30:00.123+02:00"));
        assertThat(dto.length()).isEqualTo(new QuantityValue(new BigDecimal("30"), null, "min", "http://unitsofmeasure.org", "min"));
        assertThat(dto.reasonCodes()).containsExactly(new Concept("Synthetic reason", List.of()));
        assertThat(dto.reasonReferences()).extracting(SubjectReference::reference)
                .containsExactly("Condition/synthetic-condition", "Observation/synthetic-observation");
        assertThat(dto.diagnoses()).containsExactly(new EncounterDto.Diagnosis(
                new SubjectReference("Condition/synthetic-condition", null, null, null), new Concept("Admission", List.of()), 1));
        assertThat(dto.locations()).containsExactly(new EncounterDto.LocationEntry(
                new SubjectReference("Location/synthetic-room", null, "Room 1", null), "completed",
                new Concept("Room", List.of()), new PeriodValue("2026-06", null)));
        assertThat(dto.serviceProvider()).isEqualTo(new SubjectReference("Organization/synthetic-clinic", null, null, null));
    }

    @Test
    void missingOptionalFieldsRemainAbsentWithEmptyImmutableLists() {
        EncounterDto dto = mapper.toDto(parse(""));
        assertThat(dto.id()).isNull();
        assertThat(dto.subject()).isNull();
        assertThat(dto.serviceType()).isNull();
        assertThat(dto.priority()).isNull();
        assertThat(dto.period()).isNull();
        assertThat(dto.length()).isNull();
        assertThat(dto.serviceProvider()).isNull();
        assertThat(dto.types()).isEmpty();
        assertThat(dto.participants()).isEmpty();
        assertThat(dto.reasonCodes()).isEmpty();
        assertThat(dto.reasonReferences()).isEmpty();
        assertThat(dto.diagnoses()).isEmpty();
        assertThat(dto.locations()).isEmpty();
        assertThatThrownBy(() -> dto.types().add(new Concept("x", List.of()))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dto.locations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Encounter.EncounterStatus.class, names = "NULL", mode = EnumSource.Mode.EXCLUDE)
    void preservesEveryR4StatusCode(Encounter.EncounterStatus status) {
        Encounter encounter = parse("");
        encounter.setStatus(status);
        assertThat(mapper.toDto(encounter).status()).isEqualTo(status.toCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026", "2026-06", "2026-06-15", "2026-06-15T12:34:56Z", "2026-06-15T12:34:56.123456+02:00"})
    void preservesPeriodPrecision(String date) {
        EncounterDto dto = mapper.toDto(parse(",\"period\":{\"start\":\"" + date + "\",\"end\":\"" + date + "\"}"));
        assertThat(dto.period()).isEqualTo(new PeriodValue(date, date));
    }

    @Test
    void missingClassCandidateUsesAllTheSameProjectionValidations() {
        Encounter candidate = parse("");
        candidate.setClass_(null);
        mapper.validateMissingClassCandidate(candidate);
        assertThat(candidate.hasClass_()).isFalse();
        assertThatThrownBy(() -> mapper.toDto(candidate)).isInstanceOf(IllegalArgumentException.class);
        for (String extra : new String[] {",\"diagnosis\":[{\"rank\":1}]", ",\"location\":[{\"status\":\"active\"}]"}) {
            Encounter invalid = parse(extra);
            assertThatThrownBy(() -> mapper.toDto(invalid)).isInstanceOf(IllegalArgumentException.class);
            invalid.setClass_(null);
            assertThatThrownBy(() -> mapper.validateMissingClassCandidate(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        candidate.setStatus(null);
        assertThatThrownBy(() -> mapper.validateMissingClassCandidate(candidate)).isInstanceOf(IllegalArgumentException.class);
        candidate.setStatus(Encounter.EncounterStatus.NULL);
        assertThatThrownBy(() -> mapper.validateMissingClassCandidate(candidate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.validateMissingClassCandidate(parse(""))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRequiredFieldsAndNullResource() {
        Encounter withoutStatus = parse("");
        withoutStatus.setStatus(null);
        Encounter nullStatus = parse("");
        nullStatus.setStatus(Encounter.EncounterStatus.NULL);
        Encounter withoutClass = parse("");
        withoutClass.setClass_(null);
        for (Encounter invalid : new Encounter[] {null, withoutStatus, nullStatus, withoutClass}) {
            assertThatThrownBy(() -> mapper.toDto(invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Encounter status and class are required");
        }
        assertThatThrownBy(() -> mapper.toDto(parse(",\"diagnosis\":[{\"use\":{\"text\":\"Admission\"}}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Encounter diagnosis condition is required");
        assertThatThrownBy(() -> mapper.toDto(parse(",\"location\":[{\"status\":\"active\"}]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Encounter location reference is required");
    }

    @Test
    void preservesEveryAllowedParticipantReferenceFormVerbatim() {
        EncounterDto dto = mapper.toDto(parse("""
                ,"participant":[
                  {"individual":{"reference":"Practitioner/p1","type":"Practitioner","display":"Synthetic doctor"}},
                  {"individual":{"reference":"PractitionerRole/r1","type":"PractitionerRole"}},
                  {"individual":{"reference":"RelatedPerson/rp1"}},
                  {"individual":{"reference":"https://example.test/fhir/Practitioner/p2/_history/3"}},
                  {"individual":{"type":"Practitioner","identifier":{"system":"urn:synthetic:staff","value":"s-1"}}}]
                """));
        assertThat(dto.participants()).extracting(EncounterDto.Participant::individual).containsExactly(
                new SubjectReference("Practitioner/p1", "Practitioner", "Synthetic doctor", null),
                new SubjectReference("PractitionerRole/r1", "PractitionerRole", null, null),
                new SubjectReference("RelatedPerson/rp1", null, null, null),
                new SubjectReference("https://example.test/fhir/Practitioner/p2/_history/3", null, null, null),
                new SubjectReference(null, "Practitioner", null, new Identifier("urn:synthetic:staff", "s-1")));
    }

    @Test
    void createdSubjectAndParticipantsMapBackToTheSameReferencesAndRoles() {
        var request = new CreateEncounterRequest("synthetic-patient", Status.UNKNOWN, EncounterClass.VR, null, null, null,
                null, null, null, List.of(new Participant("practitioner-a", List.of(ParticipantType.ATND, ParticipantType.PPRF)),
                        new Participant("practitioner-b", null)));
        EncounterDto dto = mapper.toDto(mapper.toResource(request));
        assertThat(dto.status()).isEqualTo("unknown");
        assertThat(dto.encounterClass().code()).isEqualTo("VR");
        assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
        assertThat(dto.participants()).extracting(participant -> participant.individual().reference())
                .containsExactly("Practitioner/practitioner-a", "Practitioner/practitioner-b");
        assertThat(dto.participants().getFirst().types()).containsExactly(
                new Concept(null, List.of(new Coding(ParticipantType.SYSTEM, null, "ATND", null, null))),
                new Concept(null, List.of(new Coding(ParticipantType.SYSTEM, null, "PPRF", null, null))));
        assertThat(dto.participants().get(1).types()).isEmpty();
    }

    @Test
    void dtoIsIndependentOfSubsequentResourceChanges() {
        Encounter encounter = parse(",\"type\":[{\"text\":\"Original\"}],\"subject\":{\"reference\":\"Patient/p1\"}");
        EncounterDto dto = mapper.toDto(encounter);
        encounter.addType(new CodeableConcept().setText("Added later"));
        encounter.getTypeFirstRep().setText("Changed");
        encounter.getSubject().setReference("Patient/other");
        assertThat(dto.types()).containsExactly(new Concept("Original", List.of()));
        assertThat(dto.subject().reference()).isEqualTo("Patient/p1");
    }

    // NIT-135: service provider (Organization) and locations (Location).

    private static CreateEncounterRequest associated(String serviceProviderId, List<EncounterLocation> locations) {
        return new CreateEncounterRequest("synthetic-patient", Status.IN_PROGRESS, EncounterClass.AMB, null, null, null,
                "2026-06-15T09:00:00+02:00", null, null,
                List.of(new Participant("synthetic-practitioner", List.of(ParticipantType.ATND))), serviceProviderId, locations);
    }

    @Test
    void serviceProviderAndLocationsMapToR4ReferencesInRequestOrder() {
        Encounter resource = mapper.toResource(associated("synthetic-clinic", List.of(
                new EncounterLocation("ward-b", LocationStatus.COMPLETED), new EncounterLocation("room-a", null),
                new EncounterLocation("room.c-1", LocationStatus.ACTIVE))));

        assertThat(resource.getServiceProvider().getReference()).isEqualTo("Organization/synthetic-clinic");
        assertThat(resource.getServiceProvider().hasDisplay() || resource.getServiceProvider().hasType()).isFalse();
        assertThat(resource.getLocation()).extracting(location -> location.getLocation().getReference())
                .containsExactly("Location/ward-b", "Location/room-a", "Location/room.c-1");
        assertThat(resource.getLocation().get(0).getStatus()).isEqualTo(Encounter.EncounterLocationStatus.COMPLETED);
        assertThat(resource.getLocation().get(1).hasStatus()).isFalse();
        assertThat(resource.getLocation().get(2).getStatus()).isEqualTo(Encounter.EncounterLocationStatus.ACTIVE);
        assertThat(resource.getLocation()).allSatisfy(location ->
                assertThat(location.hasPeriod() || location.hasPhysicalType()).isFalse());
        // Existing associations and mappings are unchanged.
        assertThat(resource.getSubject().getReference()).isEqualTo("Patient/synthetic-patient");
        assertThat(resource.getParticipantFirstRep().getIndividual().getReference()).isEqualTo("Practitioner/synthetic-practitioner");
        assertThat(resource.getStatus()).isEqualTo(Encounter.EncounterStatus.INPROGRESS);
        assertThat(resource.getClass_().getCode()).isEqualTo("AMB");
        assertThat(resource.getPeriod().getStartElement().getValueAsString()).isEqualTo("2026-06-15T09:00:00+02:00");
    }

    @Test
    void createdAssociationsMapBackToEncounterDto() {
        EncounterDto dto = mapper.toDto(mapper.toResource(associated("synthetic-clinic", List.of(
                new EncounterLocation("synthetic-ward", LocationStatus.PLANNED), new EncounterLocation("synthetic-room", null)))));

        assertThat(dto.serviceProvider()).isEqualTo(new SubjectReference("Organization/synthetic-clinic", null, null, null));
        assertThat(dto.locations()).containsExactly(
                new EncounterDto.LocationEntry(new SubjectReference("Location/synthetic-ward", null, null, null), "planned", null, null),
                new EncounterDto.LocationEntry(new SubjectReference("Location/synthetic-room", null, null, null), null, null, null));
        assertThat(dto.subject().reference()).isEqualTo("Patient/synthetic-patient");
        assertThat(dto.participants().getFirst().individual().reference()).isEqualTo("Practitioner/synthetic-practitioner");
    }

    @Test
    void locationStatusesAreExactlyTheFourR4Codes() {
        assertThat(java.util.Arrays.stream(LocationStatus.values()).map(LocationStatus::code))
                .containsExactly("planned", "active", "reserved", "completed");
        for (LocationStatus status : LocationStatus.values()) {
            assertThat(Encounter.EncounterLocationStatus.fromCode(status.code()).toCode()).isEqualTo(status.code());
        }
    }

    @Test
    void originalConstructorDefaultsToNoServiceProviderOrLocations() {
        var participants = List.of(new Participant("synthetic-practitioner", List.of(ParticipantType.ATND)));
        var original = new CreateEncounterRequest("synthetic-patient", Status.FINISHED, EncounterClass.AMB, null, null, null,
                null, null, null, participants);
        assertThat(original.serviceProviderId()).isNull();
        assertThat(original.locations()).isEmpty();
        assertThat(original).isEqualTo(new CreateEncounterRequest("synthetic-patient", Status.FINISHED, EncounterClass.AMB,
                null, null, null, null, null, null, participants, null, null));
        assertThat(original).isEqualTo(new CreateEncounterRequest("synthetic-patient", Status.FINISHED, EncounterClass.AMB,
                null, null, null, null, null, null, participants, null, List.of()));
        Encounter resource = mapper.toResource(original);
        assertThat(resource.hasServiceProvider() || resource.hasLocation()).isFalse();
    }

    @Test
    void locationsAreAnUnmodifiableCopyThatKeepsNullEntriesForValidation() {
        var source = new java.util.ArrayList<EncounterLocation>();
        source.add(new EncounterLocation("synthetic-ward", null));
        source.add(null);
        var request = associated(null, source);
        source.clear();
        assertThat(request.locations()).hasSize(2).containsNull();
        assertThatThrownBy(() -> request.locations().add(new EncounterLocation("x", null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> mapper.toResource(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " id", "Organization/1", "https://example.test/fhir/Organization/1",
            "../Organization/1", "id?x=1", "id#fragment", "id_name", "ä"})
    void rejectsInvalidServiceProviderIds(String serviceProviderId) {
        assertThatThrownBy(() -> mapper.toResource(associated(serviceProviderId, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
        assertThatThrownBy(() -> mapper.toResource(associated("x".repeat(65), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " id", "Location/1", "https://example.test/fhir/Location/1",
            "../Location/1", "id?x=1", "id#fragment", "id_name", "ä"})
    void rejectsInvalidLocationIds(String locationId) {
        assertThatThrownBy(() -> mapper.toResource(associated(null,
                List.of(new EncounterLocation("valid-location", null), new EncounterLocation(locationId, LocationStatus.ACTIVE)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
        assertThatThrownBy(() -> mapper.toResource(associated(null, List.of(new EncounterLocation("x".repeat(65), null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullMissingAndDuplicateLocations() {
        for (List<EncounterLocation> locations : List.of(
                java.util.Arrays.asList((EncounterLocation) null),
                java.util.Arrays.asList(new EncounterLocation("synthetic-ward", null), null),
                List.of(new EncounterLocation(null, LocationStatus.ACTIVE)),
                List.of(new EncounterLocation("synthetic-ward", null), new EncounterLocation("synthetic-ward", null)),
                List.of(new EncounterLocation("synthetic-ward", LocationStatus.PLANNED),
                        new EncounterLocation("synthetic-ward", LocationStatus.COMPLETED)))) {
            assertThatThrownBy(() -> mapper.toResource(associated("synthetic-clinic", locations)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Encounter creation request");
        }
    }
}
