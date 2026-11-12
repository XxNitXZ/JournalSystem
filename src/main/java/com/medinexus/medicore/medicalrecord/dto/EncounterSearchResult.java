package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;

public record EncounterSearchResult(List<EncounterDto> encounters, boolean complete, int excludedCount) {
    public EncounterSearchResult {
        encounters = List.copyOf(encounters);
        if (excludedCount < 0 || complete != (excludedCount == 0)) {
            throw new IllegalArgumentException("Invalid completeness metadata");
        }
    }
}
