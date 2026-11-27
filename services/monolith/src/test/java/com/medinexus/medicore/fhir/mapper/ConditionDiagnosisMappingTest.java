package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;

import com.medinexus.medicore.medicalrecord.dto.ConditionDto;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.ClinicalStatus;
import com.medinexus.medicore.medicalrecord.dto.CreateConditionRequest.VerificationStatus;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Coding;
import com.medinexus.medicore.medicalrecord.dto.ObservationDto.Concept;

/** US-05.02: diagnoses as FHIR R4 Condition, validated offline against the R4 core profiles. Fictional data. */
class ConditionDiagnosisMappingTest {

    private static final FhirContext CONTEXT = FhirContext.forR4();
    private static final String CATEGORY = "http://terminology.hl7.org/CodeSystem/condition-category";
    private final ConditionMapper mapper = new ConditionMapper();

    private static Concept category(String code, String display) {
        return new Concept(null, List.of(new Coding(CATEGORY, null, code, display, null)));
    }

    private static List<SingleValidationMessage> errors(Condition condition) {
        ValidationSupportChain support = new ValidationSupportChain(new DefaultProfileValidationSupport(CONTEXT),
                new InMemoryTerminologyServerValidationSupport(CONTEXT), new CommonCodeSystemsTerminologyService(CONTEXT));
        FhirValidator validator = CONTEXT.newValidator().registerValidatorModule(new FhirInstanceValidator(support));
        return validator.validateWithResult(condition).getMessages().stream()
                .filter(message -> message.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal()).toList();
    }

    @Test
    void codedAndTextOnlyDiagnosesAreValidFhirR4() {
        Condition coded = mapper.toResource(new CreateConditionRequest("syn-patient-1",
                new Concept("Typ 2-diabetes", List.of(new Coding("http://snomed.info/sct", null, "44054006", null, null))),
                ClinicalStatus.ACTIVE, VerificationStatus.CONFIRMED, List.of(category("encounter-diagnosis",
                        "Encounter Diagnosis")), null, "2026-10-01", null, "2026-11-12T09:30:00Z", "syn-enc-1",
                "syn-pract-1"));
        Condition textOnly = mapper.toResource(new CreateConditionRequest("syn-patient-1",
                new Concept("Misstänkt migrän", List.of()), ClinicalStatus.ACTIVE, VerificationStatus.PROVISIONAL,
                List.of(category("problem-list-item", "Problem List Item")), null, null, null,
                "2026-11-12T09:30:00Z", null, "syn-pract-1"));

        assertThat(errors(coded)).as("coded diagnosis").isEmpty();
        assertThat(errors(textOnly)).as("text-only diagnosis").isEmpty();
        assertThat(coded.getSubject().getReference()).isEqualTo("Patient/syn-patient-1");
        assertThat(coded.getVerificationStatus().getCodingFirstRep().getSystem())
                .isEqualTo("http://terminology.hl7.org/CodeSystem/condition-ver-status");
        assertThat(coded.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("confirmed");
        assertThat(coded.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(textOnly.getCode().hasCoding()).isFalse();

        // The validator does check: Condition.subject is required (1..1) in R4.
        Condition broken = textOnly.copy();
        broken.setSubject(null);
        assertThat(errors(broken)).isNotEmpty();
    }

    @Test
    void recordedDateEncounterAndRecorderAreReadBackVerbatim() {
        Condition condition = mapper.toResource(new CreateConditionRequest("syn-patient-1",
                new Concept("Astma", List.of()), ClinicalStatus.ACTIVE, VerificationStatus.CONFIRMED, List.of(), null,
                null, null, "2026-11-12T09:30:00Z", "syn-enc-1", "syn-pract-1"));
        condition.setId("c1");
        ConditionDto dto = mapper.toDto(condition);
        assertThat(dto.recordedDate()).isEqualTo("2026-11-12T09:30:00Z");
        assertThat(dto.encounter().reference()).isEqualTo("Encounter/syn-enc-1");
        assertThat(dto.recorder().reference()).isEqualTo("Practitioner/syn-pract-1");

        // A Condition created elsewhere without these elements maps to nulls.
        Condition minimal = new Condition();
        minimal.setId("c2");
        minimal.getSubject().setReference("Patient/syn-patient-1");
        ConditionDto partial = mapper.toDto(minimal);
        assertThat(partial.code()).isNull();
        assertThat(partial.recordedDate()).isNull();
        assertThat(partial.encounter()).isNull();
        assertThat(partial.recorder()).isNull();
        // The Epic 3 constructor still works and leaves the new values empty.
        assertThat(new ConditionDto("c3", null, null, null, null, List.of(), null, null, null).recorder()).isNull();
    }
}
