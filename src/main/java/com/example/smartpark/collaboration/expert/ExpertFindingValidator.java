package com.example.smartpark.collaboration.expert;

import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Enforces that a model can only cite evidence observed during this invocation. */
public final class ExpertFindingValidator {
    public ExpertFinding validate(ExpertFinding finding, Set<String> observedEvidenceRefs) {
        Objects.requireNonNull(finding, "finding");
        Set<String> observed = Set.copyOf(Objects.requireNonNull(observedEvidenceRefs, "observedEvidenceRefs"));
        List<String> refs = finding.evidenceRefs();
        boolean validRefs = !refs.isEmpty() && new HashSet<>(observed).containsAll(refs);
        if (finding.status() == FindingStatus.SUPPORTED && !validRefs) {
            return new ExpertFinding(finding.domain(), FindingStatus.INSUFFICIENT_EVIDENCE,
                    "Insufficient evidence: the finding cited unavailable evidence.", List.of(), 0,
                    List.of("repeat the domain tool lookup"));
        }
        return finding;
    }
}
