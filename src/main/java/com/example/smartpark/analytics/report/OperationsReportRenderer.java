package com.example.smartpark.analytics.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** PDF renderer that consumes only the persisted structured report snapshot. */
public final class OperationsReportRenderer {
    public static final String RENDERER_VERSION = "pdfbox-v1";
    private static final String FONT_RESOURCE = "/fonts/NotoSansSC.ttf";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Map<String, String> COLUMN_LABELS = Map.ofEntries(
            Map.entry("building_id", "楼宇编号"),
            Map.entry("building_name", "楼宇名称"),
            Map.entry("meter_id", "电表编号"),
            Map.entry("energy_kwh", "能耗（kWh）"),
            Map.entry("energy_deviation_pct", "能耗偏差率（%）"),
            Map.entry("parking_utilization_pct", "停车利用率（%）"),
            Map.entry("high_risk_alert_count", "高风险告警数"),
            Map.entry("alert_count", "告警数"),
            Map.entry("category", "事件类别"),
            Map.entry("risk_level", "风险等级"),
            Map.entry("status", "状态"),
            Map.entry("occurred_at", "发生时间"),
            Map.entry("hour_ts", "小时"),
            Map.entry("stat_date", "日期"));

    public OperationsDailyReport.Artifact render(OperationsDailyReport report, Instant createdAt) {
        byte[] bytes = pdf(report, createdAt);
        String date = FILE_DATE.withZone(ZoneId.of(report.timezone()))
                .format(report.timeWindow().toExclusive().minusNanos(1));
        String fileName = "smart-park-operations-report-" + date + ".pdf";
        return new OperationsDailyReport.Artifact(UUID.randomUUID(), "PDF", fileName,
                "application/pdf", bytes.length, createdAt, sha256(bytes),
                RENDERER_VERSION, bytes);
    }

    byte[] pdf(OperationsDailyReport report, Instant createdAt) {
        try (PDDocument document = new PDDocument();
             InputStream fontStream = OperationsReportRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
            if (fontStream == null) throw new IllegalStateException("operations report font is unavailable");
            PDFont font = PDType0Font.load(document, fontStream, true);
            applyMetadata(document, report, createdAt);
            PdfLayout layout = new PdfLayout(document, font, ZoneId.of(report.timezone()));

            layout.title(report.title());
            layout.metadata("报告编号", report.reportId().toString());
            layout.metadata("报告状态", reportStatus(report.status()));
            layout.metadata("生成时间", layout.time(report.completedAt()));
            layout.metadata("数据截至", layout.time(report.asOf()));
            layout.metadata("统计周期", layout.time(report.timeWindow().fromInclusive())
                    + " 至 " + layout.time(report.timeWindow().toExclusive()));
            layout.metadata("时区", report.timezone());
            layout.metadata("追踪编号", report.traceId().toString());

            layout.sectionHeading("摘要");
            layout.paragraph(report.summary().isBlank() ? "暂无摘要" : report.summary());

            layout.sectionHeading("分析章节");
            for (OperationsDailyReport.SectionResult section : report.sections()) {
                layout.subsectionHeading(section.title(), sectionStatus(section.status()));
                if (!section.summary().isBlank()) layout.paragraph(section.summary());
                layout.paragraph("结果行数：" + section.rowCount()
                        + (section.truncated() ? "（结果已按查询上限截断）" : ""));
                if (section.partialReason() != null) layout.notice("部分完成原因：" + section.partialReason());
                if (section.failureReason() != null) layout.notice("未完成原因：" + section.failureReason());
                if (!section.columns().isEmpty()) layout.table(section.columns(), section.rows());
            }

            layout.sectionHeading("证据引用");
            if (report.evidence().isEmpty()) {
                layout.paragraph("暂无证据引用");
            } else {
                for (OperationsDailyReport.EvidenceReference evidence : report.evidence()) {
                    layout.bullet(evidence.sourceSystem() + " / " + evidence.metric() + " / "
                            + evidence.entity() + " / " + layout.time(evidence.observationTime())
                            + " / " + evidence.summary());
                }
            }

            layout.sectionHeading("数据来源");
            if (report.sourceReferences().isEmpty()) {
                layout.paragraph("暂无数据来源记录");
            } else {
                for (OperationsDailyReport.SourceReference source : report.sourceReferences()) {
                    layout.bullet(source.sourceSystem() + " / " + source.metric() + " (" + source.unit()
                            + ") / " + sourceStatus(source.status()) + " / 截至 " + layout.time(source.asOf()));
                }
            }

            layout.finish();
            addFooters(document, font);
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            document.save(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("unable to render operations report PDF", failure);
        }
    }

    private static void applyMetadata(PDDocument document, OperationsDailyReport report, Instant createdAt) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(report.title());
        information.setAuthor("AI 智慧园区");
        information.setSubject("运营日报生成时快照");
        information.setCreator("Smart Park Operations Report / Apache PDFBox");
        Calendar timestamp = GregorianCalendar.from(createdAt.atZone(ZoneId.of("UTC")));
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        information.setCreationDate(timestamp);
        information.setModificationDate(timestamp);
        document.setDocumentInformation(information);
        document.setVersion(1.7f);
    }

    private static void addFooters(PDDocument document, PDFont font) throws IOException {
        int total = document.getNumberOfPages();
        int number = 0;
        for (PDPage page : document.getPages()) {
            number++;
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.setStrokingColor(new Color(217, 229, 242));
                stream.moveTo(48, 35);
                stream.lineTo(page.getMediaBox().getWidth() - 48, 35);
                stream.stroke();
                String footer = "AI 智慧园区运营报告  |  第 " + number + " / " + total + " 页";
                stream.beginText();
                stream.setFont(font, 7.5f);
                stream.setNonStrokingColor(new Color(105, 126, 151));
                stream.newLineAtOffset(48, 21);
                stream.showText(footer);
                stream.endText();
            }
        }
    }

    private static String reportStatus(OperationsReportStatus status) {
        return switch (status) {
            case REQUESTED -> "已请求";
            case GENERATING -> "生成中";
            case COMPLETED -> "已完成";
            case PARTIAL -> "部分完成";
            case FAILED -> "生成失败";
        };
    }

    private static String sectionStatus(OperationsReportSectionStatus status) {
        return switch (status) {
            case PENDING -> "等待生成";
            case RUNNING -> "生成中";
            case COMPLETED -> "已完成";
            case UNAVAILABLE -> "数据暂不可用";
            case FAILED -> "生成失败";
        };
    }

    private static String sourceStatus(String status) {
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "AVAILABLE", "OK" -> "可用";
            case "PARTIAL" -> "部分可用";
            case "UNAVAILABLE" -> "暂不可用";
            default -> status;
        };
    }

    private static String columnLabel(String column) {
        return COLUMN_LABELS.getOrDefault(column.toLowerCase(java.util.Locale.ROOT), column);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class PdfLayout {
        private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
        private static final float MARGIN = 48;
        private static final float TOP = PAGE_SIZE.getHeight() - MARGIN;
        private static final float BOTTOM = 50;
        private static final float CONTENT_WIDTH = PAGE_SIZE.getWidth() - MARGIN * 2;
        private final PDDocument document;
        private final PDFont font;
        private final ZoneId timezone;
        private final DateTimeFormatter timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss VV");
        private PDPageContentStream stream;
        private float y;

        private PdfLayout(PDDocument document, PDFont font, ZoneId timezone) throws IOException {
            this.document = document;
            this.font = font;
            this.timezone = timezone;
            newPage();
        }

        private String time(Instant value) {
            return value == null ? "未取得" : timestamp.withZone(timezone).format(value);
        }

        private void title(String value) throws IOException {
            text(value, 20, 18, 59, 120, 0, 10, CONTENT_WIDTH);
            rule(63, 128, 230, 1.4f, 2, 14);
        }

        private void metadata(String label, String value) throws IOException {
            ensure(18);
            textAt(label, MARGIN, y, 8.5f, 94, 116, 142);
            textAt(fit(value, font, 9, CONTENT_WIDTH - 92), MARGIN + 92, y, 9, 46, 75, 110);
            y -= 17;
        }

        private void sectionHeading(String value) throws IOException {
            ensure(38);
            y -= 10;
            stream.setNonStrokingColor(new Color(235, 244, 255));
            stream.addRect(MARGIN, y - 20, CONTENT_WIDTH, 26);
            stream.fill();
            textAt(value, MARGIN + 10, y - 12, 13, 18, 59, 120);
            y -= 32;
        }

        private void subsectionHeading(String title, String status) throws IOException {
            ensure(34);
            y -= 5;
            textAt(title, MARGIN, y, 11, 25, 67, 119);
            float statusWidth = width(status, font, 8) + 12;
            stream.setNonStrokingColor(new Color(239, 247, 244));
            stream.addRect(MARGIN + CONTENT_WIDTH - statusWidth, y - 4, statusWidth, 16);
            stream.fill();
            textAt(status, MARGIN + CONTENT_WIDTH - statusWidth + 6, y, 8, 43, 126, 101);
            y -= 22;
        }

        private void paragraph(String value) throws IOException {
            text(value, 9, 52, 78, 108, 0, 8, CONTENT_WIDTH);
        }

        private void notice(String value) throws IOException {
            List<String> lines = wrap(value, font, 8.5f, CONTENT_WIDTH - 18, 12);
            float height = lines.size() * 12 + 10;
            ensure(height + 5);
            stream.setNonStrokingColor(new Color(255, 248, 229));
            stream.addRect(MARGIN, y - height + 5, CONTENT_WIDTH, height);
            stream.fill();
            drawLines(lines, MARGIN + 9, y - 7, 8.5f, 12, 112, 87, 35);
            y -= height + 5;
        }

        private void bullet(String value) throws IOException {
            text("- " + value, 8.5f, 62, 86, 113, 0, 6, CONTENT_WIDTH);
        }

        private void table(List<String> columns, List<List<Object>> rows) throws IOException {
            if (columns.isEmpty()) return;
            int count = columns.size();
            float[] widths = new float[count];
            for (int index = 0; index < count; index++) widths[index] = CONTENT_WIDTH / count;
            List<String> headers = columns.stream().map(OperationsReportRenderer::columnLabel).toList();
            drawTableRow(headers, widths, true);
            for (List<Object> row : rows) {
                List<String> values = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    values.add(index < row.size() && row.get(index) != null ? String.valueOf(row.get(index)) : "");
                }
                float rowHeight = tableRowHeight(values, widths, false);
                if (y - rowHeight < BOTTOM) {
                    newPage();
                    drawTableRow(headers, widths, true);
                }
                drawTableRow(values, widths, false);
            }
            y -= 9;
        }

        private void drawTableRow(List<String> values, float[] widths, boolean header) throws IOException {
            float height = tableRowHeight(values, widths, header);
            ensure(height);
            float x = MARGIN;
            for (int index = 0; index < widths.length; index++) {
                float cellWidth = widths[index];
                stream.setNonStrokingColor(new Color(header ? 238 : 255,
                        header ? 245 : 255, header ? 252 : 255));
                stream.addRect(x, y - height, cellWidth, height);
                stream.fill();
                stream.setStrokingColor(new Color(218, 230, 242));
                stream.addRect(x, y - height, cellWidth, height);
                stream.stroke();
                List<String> lines = wrap(values.get(index), font, header ? 8 : 7.5f, cellWidth - 10, 12);
                drawLines(lines, x + 5, y - 11, header ? 8 : 7.5f, 10,
                        header ? 63 : 59, header ? 91 : 82, header ? 122 : 108);
                x += cellWidth;
            }
            y -= height;
        }

        private float tableRowHeight(List<String> values, float[] widths, boolean header) throws IOException {
            int lineCount = 1;
            for (int index = 0; index < widths.length; index++) {
                lineCount = Math.max(lineCount,
                        wrap(values.get(index), font, header ? 8 : 7.5f, widths[index] - 10, 12).size());
            }
            return Math.max(header ? 24 : 22, lineCount * 10 + 10);
        }

        private void text(String value, float size, int red, int green, int blue,
                          float before, float after, float maxWidth) throws IOException {
            List<String> lines = wrap(value, font, size, maxWidth, Integer.MAX_VALUE);
            float leading = size + 4;
            ensure(before + leading);
            y -= before;
            for (String line : lines) {
                if (y - leading < BOTTOM) newPage();
                textAt(line, MARGIN, y, size, red, green, blue);
                y -= leading;
            }
            y -= after;
        }

        private void drawLines(List<String> lines, float x, float startY, float size, float leading,
                               int red, int green, int blue) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setLeading(leading);
            stream.setNonStrokingColor(new Color(red, green, blue));
            stream.newLineAtOffset(x, startY);
            for (String line : lines) {
                stream.showText(line);
                stream.newLine();
            }
            stream.endText();
        }

        private void textAt(String value, float x, float atY, float size,
                            int red, int green, int blue) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(new Color(red, green, blue));
            stream.newLineAtOffset(x, atY);
            stream.showText(printable(value, font));
            stream.endText();
        }

        private void rule(int red, int green, int blue, float width, float before, float after) throws IOException {
            ensure(before + after + width);
            y -= before;
            stream.setStrokingColor(new Color(red, green, blue));
            stream.setLineWidth(width);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + CONTENT_WIDTH, y);
            stream.stroke();
            y -= after;
        }

        private void ensure(float required) throws IOException {
            if (y - required < BOTTOM) newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PAGE_SIZE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = TOP;
            textAt("AI 智慧园区  /  运营日报", MARGIN, y, 8, 70, 105, 145);
            y -= 22;
        }

        private void finish() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private static List<String> wrap(String raw, PDFont font, float size,
                                         float maxWidth, int maxLines) throws IOException {
            String value = printable(raw, font);
            List<String> lines = new ArrayList<>();
            String[] paragraphs = value.split("\\n", -1);
            for (int paragraphIndex = 0; paragraphIndex < paragraphs.length; paragraphIndex++) {
                String paragraph = paragraphs[paragraphIndex];
                if (paragraph.isEmpty()) {
                    lines.add("");
                    if (lines.size() == maxLines) {
                        if (paragraphIndex < paragraphs.length - 1) {
                            markTruncated(lines, font, size, maxWidth);
                        }
                        return lines;
                    }
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (int offset = 0; offset < paragraph.length();) {
                    int codePoint = paragraph.codePointAt(offset);
                    String character = new String(Character.toChars(codePoint));
                    String candidate = line + character;
                    if (line.length() > 0 && width(candidate, font, size) > maxWidth) {
                        lines.add(line.toString());
                        if (lines.size() == maxLines) {
                            markTruncated(lines, font, size, maxWidth);
                            return lines;
                        }
                        line.setLength(0);
                    }
                    line.append(character);
                    offset += Character.charCount(codePoint);
                }
                if (line.length() > 0) lines.add(line.toString());
                if (lines.size() == maxLines) {
                    if (paragraphIndex < paragraphs.length - 1) {
                        markTruncated(lines, font, size, maxWidth);
                    }
                    return lines;
                }
            }
            return lines.isEmpty() ? List.of("") : lines;
        }

        private static String fit(String value, PDFont font, float size, float maxWidth) throws IOException {
            String printable = printable(value, font);
            if (width(printable, font, size) <= maxWidth) return printable;
            String suffix = "...";
            StringBuilder result = new StringBuilder();
            for (int offset = 0; offset < printable.length();) {
                int codePoint = printable.codePointAt(offset);
                String candidate = result + new String(Character.toChars(codePoint)) + suffix;
                if (width(candidate, font, size) > maxWidth) break;
                result.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            }
            return result + suffix;
        }

        private static String printable(String value, PDFont font) throws IOException {
            if (value == null) return "";
            String normalized = value.replace('\r', ' ').replace('\t', ' ')
                    .replace('\u2011', '-').replace('\u2013', '-').replace('\u2014', '-');
            StringBuilder output = new StringBuilder(normalized.length());
            for (int offset = 0; offset < normalized.length();) {
                int codePoint = normalized.codePointAt(offset);
                if (codePoint == '\n') output.append('\n');
                else if (Character.isISOControl(codePoint)) output.append(' ');
                else {
                    String character = new String(Character.toChars(codePoint));
                    try {
                        font.encode(character);
                        output.append(character);
                    } catch (IllegalArgumentException unsupported) {
                        output.append('?');
                    }
                }
                offset += Character.charCount(codePoint);
            }
            return output.toString();
        }

        private static float width(String value, PDFont font, float size) throws IOException {
            return font.getStringWidth(value) / 1000 * size;
        }

        private static void markTruncated(List<String> lines, PDFont font,
                                          float size, float maxWidth) throws IOException {
            int lastIndex = lines.size() - 1;
            String value = lines.get(lastIndex);
            String suffix = "...";
            while (!value.isEmpty() && width(value + suffix, font, size) > maxWidth) {
                int codePoint = value.codePointBefore(value.length());
                value = value.substring(0, value.length() - Character.charCount(codePoint));
            }
            lines.set(lastIndex, value + suffix);
        }
    }
}
