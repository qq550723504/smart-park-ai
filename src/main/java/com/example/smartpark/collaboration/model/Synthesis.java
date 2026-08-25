package com.example.smartpark.collaboration.model;

import java.util.List;
import java.util.Objects;

public record Synthesis(
        FindingStatus status,
        String conclusion,
        List<String> evidenceRefs,
        double confidence,
        List<String> uncertainties) {
    public Synthesis {
        Objects.requireNonNull(status, "status");
        if (conclusion == null || conclusion.isBlank()) throw new IllegalArgumentException("conclusion must not be blank");
        evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
        uncertainties = List.copyOf(uncertainties == null ? List.of() : uncertainties);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be between 0 and 1");
        if (status != FindingStatus.SUPPORTED && confidence > 0) throw new IllegalArgumentException("non-supported synthesis cannot carry confidence");
    }
}
