package com.medinexus.medicore.fhir.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.InstantType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;

/** US-05.01: clinical notes as FHIR R4 DocumentReference, validated offline against the R4 core profiles. */
class ClinicalNoteMapperTest {

    private static final FhirContext CONTEXT = FhirContext.forR4();
    private static final Instant CREATED = Instant.parse("2026-11-12T09:30:00Z");
    // Fictional note with Swedish letters, emoji and line breaks.
    private static final String TEXT = "Patienten mår bättre.\nÅterbesök om två veckor. Blodtryck 120/80 🙂\n\n– Läkare";

    private final ClinicalNoteMapper mapper = new ClinicalNoteMapper();

    private DocumentReference note() {
        return mapper.toResource("syn-patient-1", "syn-enc-1", "syn-pract-1", TEXT, CREATED);
    }

    @Test
    void aNoteReferencesThePatientEncounterAndAuthorAndCarriesPlainUtf8Text() {
        DocumentReference note = note();

        assertThat(note.hasId()).isFalse();
        assertThat(note.getStatus()).isEqualTo(DocumentReferenceStatus.CURRENT);
        assertThat(note.getSubject().getReference()).isEqualTo("Patient/syn-patient-1");
        assertThat(note.getContext().getEncounter()).singleElement()
                .satisfies(encounter -> assertThat(encounter.getReference()).isEqualTo("Encounter/syn-enc-1"));
        assertThat(note.getAuthor()).singleElement()
                .satisfies(author -> assertThat(author.getReference()).isEqualTo("Practitioner/syn-pract-1"));
        assertThat(note.getDateElement().getValueAsString()).isEqualTo("2026-11-12T09:30:00+00:00");
        assertThat(note.getType().getCoding()).singleElement().satisfies(coding -> {
            assertThat(coding.getSystem()).isEqualTo("http://loinc.org");
            assertThat(coding.getCode()).isEqualTo("11506-3");
            assertThat(coding.getDisplay()).isEqualTo("Progress note");
        });
        Attachment attachment = note.getContentFirstRep().getAttachment();
        assertThat(note.getContent()).hasSize(1);
        assertThat(attachment.getContentType()).isEqualTo("text/plain");
        assertThat(attachment.getData()).isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8));
        assertThat(attachment.getSize()).isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(attachment.hasUrl()).isFalse();
    }

    @Test
    void theSerializedNoteIsBase64Utf8AndSurvivesAStrictJsonRoundTrip() {
        String json = CONTEXT.newJsonParser().encodeResourceToString(note());
        assertThat(json).contains("\"data\":\"" + Base64.getEncoder().encodeToString(TEXT.getBytes(StandardCharsets.UTF_8)))
                .doesNotContain("bättre");

        var strict = CONTEXT.newJsonParser().setParserErrorHandler(new StrictErrorHandler());
        DocumentReference parsed = strict.parseResource(DocumentReference.class, json);
        assertThat(mapper.content(parsed)).isEqualTo(new ClinicalNoteMapper.NoteContent(TEXT, true));
        assertThat(mapper.createdAt(parsed)).isEqualTo("2026-11-12T09:30:00+00:00");
        assertThat(mapper.status(parsed)).isEqualTo("current");
        assertThat(mapper.type(parsed)).isEqualTo("Clinical note");
    }

    @Test
    void theCreatedDocumentReferenceIsValidFhirR4() {
        ValidationSupportChain support = new ValidationSupportChain(new DefaultProfileValidationSupport(CONTEXT),
                new InMemoryTerminologyServerValidationSupport(CONTEXT), new CommonCodeSystemsTerminologyService(CONTEXT));
        FhirValidator validator = CONTEXT.newValidator().registerValidatorModule(new FhirInstanceValidator(support));

        List<SingleValidationMessage> errors = validator.validateWithResult(note()).getMessages().stream()
                .filter(message -> message.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal()).toList();

        assertThat(errors).as("FHIR R4 validation errors").isEmpty();
        // A note without content is not valid R4 (content is 1..*), so the validator does check the resource.
        DocumentReference incomplete = note();
        incomplete.getContent().clear();
        assertThat(validator.validateWithResult(incomplete).getMessages())
                .anyMatch(message -> message.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal());
    }

    @Test
    void incompleteOrUnsafeInputIsRejected() {
        for (String[] ids : List.of(new String[] {null, "e", "p"}, new String[] {"Patient/x", "e", "p"},
                new String[] {"x", "http://evil.example/Encounter/1", "p"}, new String[] {"x", "e", "bad id"})) {
            assertThatThrownBy(() -> mapper.toResource(ids[0], ids[1], ids[2], TEXT, CREATED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> mapper.toResource("x", "e", "p", " \n ", CREATED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.toResource("x", "e", "p", TEXT, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "TEXT/PLAIN", "text/plain; charset=utf-8", "text/plain;charset=\"UTF-8\""})
    void plainUtf8TextIsDecoded(String contentType) {
        DocumentReference note = new DocumentReference();
        note.addContent().setAttachment(new Attachment().setContentType(contentType)
                .setData("Hej då".getBytes(StandardCharsets.UTF_8)));
        assertThat(mapper.content(note)).isEqualTo(new ClinicalNoteMapper.NoteContent("Hej då", true));
    }

    @Test
    void otherFormatsMissingDataUrlsAndInvalidBytesAreUnavailableAndNeverFetched() {
        record Case(String name, Attachment attachment) {
        }
        for (Case c : List.of(
                new Case("no data", new Attachment().setContentType("text/plain")),
                new Case("url only", new Attachment().setContentType("text/plain").setUrl("http://evil.example/note.txt")),
                new Case("pdf", new Attachment().setContentType("application/pdf").setData(new byte[] {37, 80, 68, 70})),
                new Case("html", new Attachment().setContentType("text/html").setData("<b>x</b>".getBytes(StandardCharsets.UTF_8))),
                new Case("latin-1", new Attachment().setContentType("text/plain; charset=iso-8859-1").setData(new byte[] {72})),
                new Case("no type", new Attachment().setData("x".getBytes(StandardCharsets.UTF_8))),
                new Case("invalid utf-8", new Attachment().setContentType("text/plain").setData(new byte[] {(byte) 0xC3, 0x28})),
                new Case("oversized", new Attachment().setContentType("text/plain")
                        .setData(new byte[ClinicalNoteMapper.MAX_DECODED_BYTES + 1])))) {
            DocumentReference note = new DocumentReference();
            note.addContent().setAttachment(c.attachment());
            assertThat(mapper.content(note)).as(c.name()).isEqualTo(new ClinicalNoteMapper.NoteContent(null, false));
        }
        assertThat(mapper.content(new DocumentReference()).available()).isFalse();
        assertThat(mapper.content(null).available()).isFalse();
    }

    @Test
    void theFirstReadablePlainTextAttachmentIsUsed() {
        DocumentReference note = new DocumentReference();
        note.addContent().setAttachment(new Attachment().setContentType("application/pdf").setUrl("http://x.example/a.pdf"));
        note.addContent().setAttachment(new Attachment().setContentType("text/plain").setData("Andra".getBytes(StandardCharsets.UTF_8)));
        assertThat(mapper.content(note).text()).isEqualTo("Andra");
    }

    @Test
    void olderOrPartialNotesFallBackSafely() {
        DocumentReference note = new DocumentReference();
        assertThat(mapper.createdAt(note)).isNull();
        assertThat(mapper.status(note)).isNull();
        assertThat(mapper.type(note)).isNull();
        note.getMeta().setLastUpdatedElement(new InstantType("2026-01-02T03:04:05.000+00:00"));
        assertThat(mapper.createdAt(note)).isEqualTo("2026-01-02T03:04:05.000+00:00");
        note.getType().addCoding().setDisplay("Consult note");
        assertThat(mapper.type(note)).isEqualTo("Consult note");
        note.setStatus(DocumentReferenceStatus.SUPERSEDED);
        assertThat(mapper.status(note)).isEqualTo("superseded");
    }
}
