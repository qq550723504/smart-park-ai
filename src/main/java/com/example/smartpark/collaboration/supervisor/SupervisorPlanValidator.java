package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SupervisorPlanValidator {
    public SupervisorPlan validate(SupervisorPlan plan) {
        Set<ExpertDomain> expected = expectedDomains(plan.normalizedQuestion());
        if (expected.isEmpty()) {
            throw new SupervisorPlanValidationException("question is ambiguous or outside expert collaboration scope");
        }
        if (!plan.selectedDomains().equals(expected)) {
            throw new SupervisorPlanValidationException("selectedDomains do not cover the question: expected " + expected);
        }
        return plan;
    }

    public Set<ExpertDomain> expectedDomains(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        EnumSet<ExpertDomain> domains = EnumSet.noneOf(ExpertDomain.class);
        if (containsAny(text, "energy", "consumption", "kwh", "baseline", "能耗", "用电", "电量")) {
            domains.add(ExpertDomain.ENERGY);
        }
        if (containsAny(text, "device", "offline", "hvac", "equipment", "冷机", "设备", "离线")) {
            domains.add(ExpertDomain.DEVICE);
        }
        if (containsAny(text, "security", "access", "door", "alarm", "门禁", "安防", "告警")) {
            domains.add(ExpertDomain.SECURITY);
        }
        return Set.copyOf(domains);
    }

    private static final Map<String, java.util.regex.Pattern> TOKEN_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (isAsciiToken(term)) {
                // English keywords must match on token boundaries: a raw
                // substring match makes "outdoor" select the SECURITY domain
                // via "door" and invalidates otherwise correct plans.
                if (TOKEN_PATTERNS.computeIfAbsent(term,
                                ignored -> java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(term) + "\\b"))
                        .matcher(text).find()) {
                    return true;
                }
            } else if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiToken(String term) {
        return term.matches("[a-z0-9_]+");
    }

    public static final class SupervisorPlanValidationException extends IllegalArgumentException {
        public SupervisorPlanValidationException(String message) { super(message); }
    }
}
