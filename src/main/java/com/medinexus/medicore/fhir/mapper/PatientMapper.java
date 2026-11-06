package com.medinexus.medicore.fhir.mapper;

import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.patient.dto.PatientDto;
import com.medinexus.medicore.patient.dto.PatientDto.PatientIdentifier;
import com.medinexus.medicore.patient.dto.PatientDto.PatientName;

@Component
public class PatientMapper {

    public PatientDto toDto(Patient patient) {
        if (patient == null) {
            throw new IllegalArgumentException("Patient is required");
        }
        return new PatientDto(patient.getIdElement().getIdPart(),
                patient.getIdentifier().stream().map(identifier ->
                        new PatientIdentifier(identifier.getSystem(), identifier.getValue())).toList(),
                patient.getName().stream().map(name -> new PatientName(
                        name.hasUse() ? name.getUse().toCode() : null,
                        name.getText(), name.getFamily(),
                        name.getGiven().stream().map(given -> given.getValue()).filter(value -> value != null).toList()))
                        .toList(),
                patient.hasGender() ? patient.getGender().toCode() : null,
                patient.hasBirthDate() ? patient.getBirthDateElement().getValueAsString() : null);
    }
}
