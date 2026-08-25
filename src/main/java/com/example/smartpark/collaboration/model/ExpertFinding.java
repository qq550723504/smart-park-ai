package com.example.smartpark.collaboration.model;

import java.util.List;
import java.util.Objects;

public record ExpertFinding(
        ExpertDomain domain,
        FindingStatus status,
        String conclusion,
        List<String> evidenceRefs,
        double confidence,
        List<String> nextChecks) {

    public ExpertFinding {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(status, "status");
        if (conclusion == null || conclusion.isBlank()) {
            throw new IllegalArgumentException("conclusion must not be blank");
        }
        evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
        nextChecks = List.copyOf(nextChecks == null ? List.of() : nextChecks);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (status != FindingStatus.SUPPORTED && confidence > 0) {
            throw new IllegalArgumentException("non-supported findings cannot carry confidence");
        }
        // Failure is expressed by the enum itself; the conclusion text may be
        // written in any language (e.g. "设备查询超时"), so wording is not validated.
    }
}
