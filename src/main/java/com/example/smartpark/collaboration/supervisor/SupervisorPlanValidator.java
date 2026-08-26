package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.SupervisorPlan;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SupervisorPlanValidator {
    private static final java.util.regex.Pattern DEV_IDENTIFIER = java.util.regex.Pattern.compile(
            "(?i)(?<![A-Z0-9_-])(DEV-[A-Z0-9]+(?:-[A-Z0-9]+)+)(?![A-Z0-9_-])");

    public SupervisorPlan validate(SupervisorPlan plan) {
        Set<ExpertDomain> expected = expectedDomains(plan.normalizedQuestion());
        if (expected.isEmpty()) {
            throw new SupervisorPlanValidationException("question is ambiguous or outside expert collaboration scope");
        }
        if (!plan.selectedDomains().equals(expected)) {
            throw new SupervisorPlanValidationException("selectedDomains do not cover the question: expected " + expected);
        }
        if (plan.assignments().values().stream()
                .anyMatch(assignment -> !assignment.equals(plan.normalizedQuestion()))) {
            throw new SupervisorPlanValidationException(
                    "assignment must preserve the exact normalized question and entity scope");
        }
        return plan;
    }

    public Set<ExpertDomain> expectedDomains(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        EnumSet<ExpertDomain> domains = EnumSet.noneOf(ExpertDomain.class);
        boolean energyContext = containsAny(text, "energy", "consumption", "kwh", "baseline", "meter", "能耗", "用电", "电量")
                || containsEnergyDeviceIdentifier(text);
        if (energyContext) {
            domains.add(ExpertDomain.ENERGY);
        }
        if (containsAny(text, "device", "offline", "hvac", "equipment", "冷机", "设备", "离线")
                || containsNonEnergyDeviceIdentifier(text)
                || (containsEnergyDeviceIdentifier(text) && containsDeviceStatusRequest(text))) {
            domains.add(ExpertDomain.DEVICE);
        }
        // Generic alert words (告警/alarm) must NOT route to SECURITY: ordinary
        // device-alert questions would otherwise demand the security expert,
        // whose only tool looks up security events. Only security-specific
        // alert phrases select this domain.
        if (containsAny(text, "security", "access", "door", "intrusion",
                "security alarm", "access alarm", "门禁", "安防", "安防告警", "门禁告警", "入侵告警", "安全告警")) {
            domains.add(ExpertDomain.SECURITY);
        }
        if (containsEntityIdentifier(text, "SEC-")) {
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

    private static boolean containsEntityIdentifier(String text, String prefix) {
        return java.util.regex.Pattern.compile(
                        "(?i)(?<![A-Z0-9_-])" + java.util.regex.Pattern.quote(prefix)
                                + "[A-Z0-9]+(?:-[A-Z0-9]+)+(?![A-Z0-9_-])")
                .matcher(text)
                .find();
    }

    private static boolean containsNonEnergyDeviceIdentifier(String text) {
        var matcher = DEV_IDENTIFIER.matcher(text);
        while (matcher.find()) {
            if (!matcher.group(1).toUpperCase(Locale.ROOT).startsWith("DEV-ENERGY-")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEnergyDeviceIdentifier(String text) {
        var matcher = DEV_IDENTIFIER.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).toUpperCase(Locale.ROOT).startsWith("DEV-ENERGY-")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDeviceStatusRequest(String text) {
        return containsAny(text, "status", "state", "online", "offline", "当前状态", "设备状态", "运行状态", "是否在线");
    }

    public static final class SupervisorPlanValidationException extends IllegalArgumentException {
        public SupervisorPlanValidationException(String message) { super(message); }
    }
}
