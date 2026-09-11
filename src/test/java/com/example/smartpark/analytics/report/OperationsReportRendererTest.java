package com.example.smartpark.analytics.report;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

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
    void emitsReadablePdfWithFixedSafeFilenameAndConsistentIntegrityMetadata() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OperationsDailyReport.SectionResult section = new OperationsDailyReport.SectionResult(
                "ENERGY", "能耗基线偏差", "question", OperationsReportSectionStatus.COMPLETED,
                "创新中心偏差为 12%，请持续核查。<script>alert(1)</script>", 1, false,
                List.of("楼宇", "偏差率"), List.of(List.of((Object) "创新中心", 12)), java.util.Map.of(),
                List.of(), List.of(), null, null, UUID.randomUUID());
        OperationsDailyReport report = new OperationsDailyReport(reportId, OperationsReportRequest.DAILY,
                "智慧园区运营日报", OperationsReportStatus.COMPLETED, "demo-role:OPERATOR", "OPERATOR",
                NOW, NOW, NOW, new OperationsReportRequest.TimeWindow(NOW.minusSeconds(3600), NOW),
                "Asia/Shanghai", NOW, "本次报告已完成。", List.of(section), List.of(), List.of(), runId, runId,
                OperationsDailyReport.CURRENT_SCHEMA_VERSION, OperationsDailyReport.CURRENT_GENERATION_VERSION,
                null, "key", "fingerprint", 1, List.of());

        OperationsDailyReport.Artifact artifact = new OperationsReportRenderer().render(report, NOW);

        assertThat(artifact.format()).isEqualTo("PDF");
        assertThat(artifact.fileName()).isEqualTo("smart-park-operations-report-2026-09-09.pdf");
        assertThat(artifact.contentType()).isEqualTo("application/pdf");
        assertThat(artifact.rendererVersion()).isEqualTo(OperationsReportRenderer.RENDERER_VERSION);
        assertThat(artifact.content()).startsWith(new byte[] {'%', 'P', 'D', 'F', '-'});
        assertThat(artifact.size()).isEqualTo(artifact.content().length);
        assertThat(artifact.size()).isLessThanOrEqualTo(256 * 1024L);
        assertThat(artifact.checksum()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(artifact.content())));

        try (PDDocument document = Loader.loadPDF(artifact.content())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("智慧园区运营日报");
            assertThat(text).contains("智慧园区运营日报", "报告状态", "已完成", "能耗基线偏差",
                    "结果行数：1", "创新中心", "12", "<script>alert(1)</script>");
        }

        assertThatThrownBy(() -> new OperationsDailyReport.Artifact(UUID.randomUUID(), "PDF",
                "..\\evil\r\nheader.pdf", "application/pdf", 0, NOW, "checksum", "v1", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsafe");
    }

    @Test
    void paginatesLongSnapshotTextWithoutDroppingTheTail() throws Exception {
        UUID runId = UUID.randomUUID();
        OperationsDailyReport report = new OperationsDailyReport(UUID.randomUUID(), OperationsReportRequest.DAILY,
                "智慧园区运营日报", OperationsReportStatus.COMPLETED, "demo-role:OPERATOR", "OPERATOR",
                NOW, NOW, NOW, new OperationsReportRequest.TimeWindow(NOW.minusSeconds(3600), NOW),
                "Asia/Shanghai", NOW, "开始。" + "这是需要完整分页的生成时摘要。".repeat(800) + "结束。",
                List.of(), List.of(), List.of(), runId, runId, OperationsDailyReport.CURRENT_SCHEMA_VERSION,
                OperationsDailyReport.CURRENT_GENERATION_VERSION, null, "long-key", "long-fingerprint", 1,
                List.of());

        OperationsDailyReport.Artifact artifact = new OperationsReportRenderer().render(report, NOW);

        try (PDDocument document = Loader.loadPDF(artifact.content())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(document)).contains("开始。", "结束。");
        }
    }
}
