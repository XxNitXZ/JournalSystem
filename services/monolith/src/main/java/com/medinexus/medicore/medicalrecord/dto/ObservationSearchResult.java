package com.medinexus.medicore.medicalrecord.dto;

import java.util.List;

/** Completeness describes exclusions from a fully traversed bounded search, not the whole clinical record. */
public record ObservationSearchResult(List<ObservationDto> observations, boolean complete, int excludedCount) {
    public ObservationSearchResult {
        observations = List.copyOf(observations);
        if (excludedCount < 0 || complete != (excludedCount == 0)) throw new IllegalArgumentException("Invalid completeness metadata");
    }
}
