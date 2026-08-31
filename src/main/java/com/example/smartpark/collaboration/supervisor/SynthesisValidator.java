package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.Synthesis;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates synthesis selection and provenance without interpreting free text. */
public final class SynthesisValidator {

    public Synthesis validate(Synthesis synthesis,
                              List<ExpertFinding> findings,
                              Set<ExpertDomain> selectedDomains) {
        return validate(synthesis, findings, selectedDomains, false);
    }

    public Synthesis validateModelSynthesis(Synthesis synthesis,
                                            List<ExpertFinding> findings,
                                            Set<ExpertDomain> selectedDomains) {
        return validate(synthesis, findings, selectedDomains, true);
    }

    private Synthesis validate(Synthesis synthesis,
                               List<ExpertFinding> findings,
                               Set<ExpertDomain> selectedDomains,
                               boolean modelConclusion) {
        List<ExpertFinding> safeFindings = List.copyOf(findings);
        Set<ExpertDomain> safeSelection = Set.copyOf(selectedDomains);
        Map<ExpertDomain, ExpertFinding> byDomain = indexByDomain(safeFindings);
        Set<ExpertDomain> supportedDomains = safeFindings.stream()
                .filter(finding -> finding.status() == FindingStatus.SUPPORTED)
                .map(ExpertFinding::domain)
                .collect(Collectors.toSet());

        if (synthesis.status() == FindingStatus.SUPPORTED && safeSelection.isEmpty()) {
            throw new IllegalArgumentException("supported synthesis requires a selected SUPPORTED finding");
        }
        if (synthesis.status() != FindingStatus.SUPPORTED && !safeSelection.isEmpty()) {
            throw new IllegalArgumentException("non-supported synthesis cannot select findings");
        }
        if (synthesis.status() == FindingStatus.SUPPORTED && !safeSelection.equals(supportedDomains)) {
            throw new IllegalArgumentException(
                    "synthesis selected findings must include all SUPPORTED findings");
        }
        if (synthesis.status() != FindingStatus.SUPPORTED && !supportedDomains.isEmpty()) {
            throw new IllegalArgumentException(
                    "synthesis must be SUPPORTED when any SUPPORTED finding exists");
        }
        for (ExpertDomain domain : safeSelection) {
            ExpertFinding finding = byDomain.get(domain);
            if (finding == null || finding.status() != FindingStatus.SUPPORTED) {
                throw new IllegalArgumentException("selected domain must have a SUPPORTED finding: " + domain);
            }
        }

        Set<String> allowedRefs = safeSelection.stream()
                .map(byDomain::get)
                .flatMap(finding -> finding.evidenceRefs().stream())
                .collect(Collectors.toSet());
        Set<String> actualRefs = Set.copyOf(synthesis.evidenceRefs());
        if (!allowedRefs.equals(actualRefs)) {
            throw new IllegalArgumentException(
                    "synthesis evidence must exactly cover all selected findings");
        }
        if (synthesis.status() == FindingStatus.SUPPORTED && synthesis.evidenceRefs().isEmpty()) {
            throw new IllegalArgumentException("supported synthesis must cite selected findings");
        }
        if (synthesis.status() != FindingStatus.SUPPORTED && !synthesis.evidenceRefs().isEmpty()) {
            throw new IllegalArgumentException("non-supported synthesis cannot cite evidence");
        }

        String expectedConclusion = safeSelection.stream()
                .map(byDomain::get)
                .sorted(Comparator.comparing(ExpertFinding::domain))
                .map(ExpertFinding::conclusion)
                .collect(Collectors.joining("；"));
        if (expectedConclusion.isBlank()) {
            expectedConclusion = synthesis.status() == FindingStatus.FAILED
                    ? "专家协作失败" : "没有可验证的专家结论";
        }
        if (!modelConclusion && !expectedConclusion.equals(synthesis.conclusion())) {
            throw new IllegalArgumentException("synthesis conclusion must be derived from selected findings");
        }

        boolean hasUncertainty = safeFindings.stream()
                .anyMatch(finding -> finding.status() != FindingStatus.SUPPORTED);
        if (hasUncertainty && synthesis.uncertainties().isEmpty()) {
            throw new IllegalArgumentException("partial failures or insufficient evidence must be disclosed");
        }
        return synthesis;
    }

    private static Map<ExpertDomain, ExpertFinding> indexByDomain(List<ExpertFinding> findings) {
        Map<ExpertDomain, ExpertFinding> byDomain = new HashMap<>();
        for (ExpertFinding finding : findings) {
            if (byDomain.put(finding.domain(), finding) != null) {
                throw new IllegalArgumentException("duplicate finding domain: " + finding.domain());
            }
        }
        return byDomain;
    }
}
