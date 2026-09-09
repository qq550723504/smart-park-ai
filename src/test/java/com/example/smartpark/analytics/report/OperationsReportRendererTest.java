package com.example.smartpark.analytics.report;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsReportRendererTest {
    private static final Instant NOW = Instant.parse("2026-09-09T02:00:00Z");

    @Test
    void emitsFixedSafeFilenameEscapedMarkdownAndConsistentIntegrityMetadata() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OperationsDailyReport.SectionResult section = new OperationsDailyReport.SectionResult(
                "ENERGY", "<script>alert(1)</script>", "question", OperationsReportSectionStatus.COMPLETED,
                "![remote](https://evil.invalid/pixel)", 1, false, List.of("metric|name"),
                List.of(List.of((Object) "<img src=x onerror=alert(1)>")), java.util.Map.of(),
                List.of(), List.of(), null, null, UUID.randomUUID());
        OperationsDailyReport report = new OperationsDailyReport(reportId, OperationsReportRequest.DAILY,
                "智慧园区运营日报", OperationsReportStatus.COMPLETED, "demo-role:OPERATOR", "OPERATOR",
                NOW, NOW, NOW, new OperationsReportRequest.TimeWindow(NOW.minusSeconds(3600), NOW),
                "Asia/Shanghai", NOW, "done", List.of(section), List.of(), List.of(), runId, runId,
                1, "v1", null, "key", "fingerprint", 1, List.of());

        OperationsDailyReport.Artifact artifact = new OperationsReportRenderer().render(report, NOW);

        assertThat(artifact.fileName()).isEqualTo("smart-park-operations-report-2026-09-09.md");
        assertThat(artifact.content()).doesNotContain("<script>", "<img", "![remote]")
                .contains("&lt;script&gt;", "\\!\\[remote\\]", "metric\\|name");
        assertThat(artifact.size()).isEqualTo(artifact.content().getBytes(StandardCharsets.UTF_8).length);
        assertThat(artifact.checksum()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(artifact.content().getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> new OperationsDailyReport.Artifact(UUID.randomUUID(), "MARKDOWN",
                "..\\evil\r\nheader.md", "text/markdown", 0, NOW, "checksum", "v1", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsafe");
    }
}
