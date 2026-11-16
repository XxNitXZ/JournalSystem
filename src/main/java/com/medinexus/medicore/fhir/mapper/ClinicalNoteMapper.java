package com.medinexus.medicore.fhir.mapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;

/**
 * Clinical notes as FHIR R4 {@code DocumentReference} (US-05.01): plain text, inline, UTF-8.
 *
 * <p>A note is a document about the patient, not a measurement or a diagnosis, so neither Observation nor Condition
 * fits; DocumentReference is FHIR's resource for clinical documents and is used for clinical notes (for example by
 * US Core). The type is the standard LOINC code {@value #TYPE_CODE} "{@value #TYPE_DISPLAY}" (code system
 * {@value #LOINC}); no local code system is invented.
 */
@Component
public class ClinicalNoteMapper {

    public static final String LOINC = "http://loinc.org";
    public static final String TYPE_CODE = "11506-3";
    public static final String TYPE_DISPLAY = "Progress note";
    public static final String CONTENT_TYPE = "text/plain";
    /** Notes created elsewhere that are larger than this are not decoded (MediCore itself writes far less). */
    static final int MAX_DECODED_BYTES = 256 * 1024;

    private static final String LOGICAL_ID = "[A-Za-z0-9.-]{1,64}";

    /** The decoded text of a note, or {@code available == false} when MediCore cannot show it (text is then null). */
    public record NoteContent(String text, boolean available) {

        static NoteContent unavailable() {
            return new NoteContent(null, false);
        }
    }

    /**
     * A new note: {@code status=current}, the LOINC type, {@code subject=Patient/{patientId}},
     * {@code context.encounter=Encounter/{encounterId}}, {@code author=Practitioner/{practitionerId}},
     * {@code date} (UTC, seconds) and one {@code content.attachment} with {@code contentType=text/plain} and the
     * UTF-8 bytes as {@code data} (base64 is written by HAPI's serializer). IDs are validated logical IDs, never URLs.
     */
    public DocumentReference toResource(String patientId, String encounterId, String practitionerId, String text,
            Instant created) {
        if (!isLogicalId(patientId) || !isLogicalId(encounterId) || !isLogicalId(practitionerId) || text == null
                || text.isBlank() || created == null) {
            throw new IllegalArgumentException("A complete clinical note is required");
        }
        DocumentReference note = new DocumentReference();
        note.setStatus(DocumentReferenceStatus.CURRENT);
        note.setType(new CodeableConcept(new Coding(LOINC, TYPE_CODE, TYPE_DISPLAY)).setText("Clinical note"));
        note.setSubject(new Reference("Patient/" + patientId));
        note.setDateElement(new InstantType(Date.from(created), TemporalPrecisionEnum.SECOND,
                TimeZone.getTimeZone("UTC")));
        note.addAuthor(new Reference("Practitioner/" + practitionerId));
        note.getContext().addEncounter(new Reference("Encounter/" + encounterId));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        note.addContent().setAttachment(new Attachment().setContentType(CONTENT_TYPE).setData(bytes)
                .setSize(bytes.length));
        return note;
    }

    /**
     * The text of the first inline plain-text attachment. Only {@code text/plain} (optionally with
     * {@code charset=utf-8}) with inline data is read, as strict UTF-8; attachments that only have a URL are never
     * fetched. Anything else (no content, other formats, invalid UTF-8, oversized data) is {@code unavailable}.
     */
    public NoteContent content(DocumentReference note) {
        if (note == null) return NoteContent.unavailable();
        for (DocumentReference.DocumentReferenceContentComponent content : note.getContent()) {
            Attachment attachment = content.getAttachment();
            if (attachment == null || !isPlainUtf8Text(attachment.getContentType()) || !attachment.hasData()) continue;
            byte[] data = attachment.getData();
            if (data == null || data.length > MAX_DECODED_BYTES) return NoteContent.unavailable();
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data)).toString();
                return new NoteContent(text, true);
            } catch (CharacterCodingException invalid) {
                return NoteContent.unavailable();
            }
        }
        return NoteContent.unavailable();
    }

    /** {@code date} as stored (an instant), otherwise {@code meta.lastUpdated}; null if neither is present. */
    public String createdAt(DocumentReference note) {
        if (note == null) return null;
        if (note.hasDateElement() && note.getDateElement().getValueAsString() != null) {
            return note.getDateElement().getValueAsString();
        }
        if (note.hasMeta() && note.getMeta().hasLastUpdatedElement()) {
            return note.getMeta().getLastUpdatedElement().getValueAsString();
        }
        return null;
    }

    /** The R4 status code ({@code current}, {@code superseded}, {@code entered-in-error}), or null. */
    public String status(DocumentReference note) {
        DocumentReferenceStatus status = note == null ? null : note.getStatus();
        return status == null || status == DocumentReferenceStatus.NULL ? null : status.toCode();
    }

    /** The type's text or the first coding display, or null. */
    public String type(DocumentReference note) {
        if (note == null || !note.hasType()) return null;
        if (note.getType().hasText() && !note.getType().getText().isBlank()) return note.getType().getText().strip();
        return note.getType().getCoding().stream().map(Coding::getDisplay)
                .filter(display -> display != null && !display.isBlank()).map(String::strip).findFirst().orElse(null);
    }

    static boolean isPlainUtf8Text(String contentType) {
        if (contentType == null) return false;
        String[] parts = contentType.split(";");
        if (!CONTENT_TYPE.equals(parts[0].strip().toLowerCase(Locale.ROOT))) return false;
        for (int i = 1; i < parts.length; i++) {
            String[] parameter = parts[i].split("=", 2);
            if (parameter.length == 2 && "charset".equals(parameter[0].strip().toLowerCase(Locale.ROOT))) {
                String charset = parameter[1].strip().replace("\"", "").toLowerCase(Locale.ROOT);
                if (!charset.equals("utf-8") && !charset.equals("utf8")) return false;
            }
        }
        return true;
    }

    private static boolean isLogicalId(String id) {
        return id != null && id.matches(LOGICAL_ID);
    }
}
