package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.Synthesis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SynthesisValidator {
    private static final Pattern FACT_TOKEN = Pattern.compile("(?<![A-Za-z0-9])[0-9]+(?:\\.[0-9]+)?(?![A-Za-z0-9])|\\b[A-Z]{2,}[A-Z0-9-]*[0-9][A-Z0-9-]*\\b");

    public Synthesis validate(Synthesis synthesis, List<ExpertFinding> findings) {
        Set<String> allowedRefs = new HashSet<>();
        Set<String> allowedFacts = new HashSet<>();
        for (ExpertFinding finding : findings) {
            allowedRefs.addAll(finding.evidenceRefs());
            collectFacts(finding.conclusion(), allowedFacts);
        }
        if (!allowedRefs.containsAll(synthesis.evidenceRefs())) {
            throw new IllegalArgumentException("synthesis contains evidence not present in findings");
        }
        Set<String> synthesisFacts = new HashSet<>();
        collectFacts(synthesis.conclusion(), synthesisFacts);
        synthesisFacts.removeAll(allowedFacts);
        if (!synthesisFacts.isEmpty()) {
            throw new IllegalArgumentException("synthesis contains unsupported facts: " + synthesisFacts);
        }
        boolean hasSupported = findings.stream().anyMatch(f -> f.status() == FindingStatus.SUPPORTED);
        if (!hasSupported && synthesis.status() == FindingStatus.SUPPORTED) {
            throw new IllegalArgumentException("synthesis cannot be supported without a supported finding");
        }
        boolean hasUncertainty = findings.stream().anyMatch(f -> f.status() != FindingStatus.SUPPORTED);
        if (hasUncertainty && synthesis.uncertainties().isEmpty()) {
            throw new IllegalArgumentException("partial failures or insufficient evidence must be disclosed");
        }
        return synthesis;
    }

    private static void collectFacts(String text, Set<String> target) {
        Matcher matcher = FACT_TOKEN.matcher(text == null ? "" : text);
        while (matcher.find()) target.add(matcher.group());
    }
}
