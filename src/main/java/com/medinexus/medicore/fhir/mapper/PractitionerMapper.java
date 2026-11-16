package com.medinexus.medicore.fhir.mapper;

import org.hl7.fhir.r4.model.Practitioner;
import org.springframework.stereotype.Component;

import com.medinexus.medicore.practitioner.dto.PractitionerDto;
import com.medinexus.medicore.practitioner.dto.PractitionerDto.PractitionerIdentifier;
import com.medinexus.medicore.practitioner.dto.PractitionerDto.PractitionerName;

@Component
public class PractitionerMapper {

    public PractitionerDto toDto(Practitioner practitioner) {
        if (practitioner == null) {
            throw new IllegalArgumentException("Practitioner is required");
        }
        return new PractitionerDto(practitioner.getIdElement().getIdPart(),
                practitioner.getIdentifier().stream().map(identifier ->
                        new PractitionerIdentifier(identifier.getSystem(), identifier.getValue())).toList(),
                practitioner.getName().stream().map(name -> new PractitionerName(
                        name.hasUse() ? name.getUse().toCode() : null,
                        name.getText(), name.getFamily(),
                        name.getGiven().stream().map(given -> given.getValue()).filter(value -> value != null).toList()))
                        .toList(),
                practitioner.hasActive() ? practitioner.getActive() : null);
    }
}
