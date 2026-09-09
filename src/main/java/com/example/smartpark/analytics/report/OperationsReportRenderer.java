package com.example.smartpark.analytics.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/** Deterministic Markdown renderer. It consumes only a persisted structured snapshot. */
public final class OperationsReportRenderer {
    public static final String RENDERER_VERSION = "markdown-v1";

    public OperationsDailyReport.Artifact render(OperationsDailyReport report, java.time.Instant createdAt) {
        String content = markdown(report);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of(report.timezone()))
                .format(report.timeWindow().toExclusive().minusNanos(1));
        String fileName = "smart-park-operations-report-" + date + ".md";
        return new OperationsDailyReport.Artifact(UUID.randomUUID(), "MARKDOWN", fileName,
                "text/markdown;charset=UTF-8", bytes.length, createdAt, sha256(bytes),
                RENDERER_VERSION, content);
    }

    String markdown(OperationsDailyReport report) {
        StringBuilder output = new StringBuilder(2048);
        output.append("# ").append(safe(report.title())).append("\n\n")
                .append("- Report ID: `").append(report.reportId()).append("`\n")
                .append("- Status: `").append(report.status()).append("`\n")
                .append("- Generated At: ").append(report.completedAt()).append("\n")
                .append("- Source As Of: ").append(report.asOf()).append("\n")
                .append("- Time Window: ").append(report.timeWindow().fromInclusive()).append(" to ")
                .append(report.timeWindow().toExclusive()).append("\n")
                .append("- Timezone: `").append(safe(report.timezone())).append("`\n")
                .append("- Trace: `").append(report.traceId()).append("`\n\n")
                .append("## Summary\n\n").append(safe(report.summary())).append("\n\n")
                .append("## Sections\n\n");
        for (OperationsDailyReport.SectionResult section : report.sections()) {
            output.append("### ").append(safe(section.title())).append("\n\n")
                    .append("Status: `").append(section.status()).append("`\n\n");
            if (!section.summary().isBlank()) output.append(safe(section.summary())).append("\n\n");
            if (section.partialReason() != null) output.append("Partial reason: ").append(safe(section.partialReason())).append("\n\n");
            if (section.failureReason() != null) output.append("Failure reason: ").append(safe(section.failureReason())).append("\n\n");
            if (!section.columns().isEmpty()) {
                output.append("|");
                section.columns().forEach(column -> output.append(" ").append(cell(column)).append(" |"));
                output.append("\n|");
                section.columns().forEach(column -> output.append(" --- |"));
                output.append("\n");
                for (var row : section.rows()) {
                    output.append("|");
                    row.forEach(value -> output.append(" ").append(cell(value)).append(" |"));
                    output.append("\n");
                }
                output.append("\n");
            }
        }
        output.append("## Evidence\n\n");
        for (OperationsDailyReport.EvidenceReference evidence : report.evidence()) {
            output.append("- ").append(safe(evidence.sourceSystem())).append(" / ")
                    .append(safe(evidence.metric())).append(" / ").append(safe(evidence.entity()))
                    .append(" @ ").append(evidence.observationTime()).append(" — ")
                    .append(safe(evidence.summary())).append("\n");
        }
        output.append("\n## Sources\n\n");
        for (OperationsDailyReport.SourceReference source : report.sourceReferences()) {
            output.append("- ").append(safe(source.sourceSystem())).append(" / ")
                    .append(safe(source.metric())).append(" (").append(safe(source.unit())).append(") — ")
                    .append(safe(source.status())).append(", as of ").append(source.asOf()).append("\n");
        }
        return output.toString();
    }

    private static String cell(Object value) {
        return safe(value == null ? "" : String.valueOf(value));
    }

    private static String safe(String value) {
        if (value == null) return "";
        String normalized = value.replace("\r", " ").replace("\n", " ").trim()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        StringBuilder escaped = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if ("\\`*_{}[]()#+-.!|".indexOf(character) >= 0) escaped.append('\\');
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
