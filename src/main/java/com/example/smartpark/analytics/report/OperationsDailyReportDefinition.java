package com.example.smartpark.analytics.report;

import java.util.List;

/** Single source of truth for the customer-facing operations report template. */
public final class OperationsDailyReportDefinition {

    private static final List<OperationsReportSection> SECTIONS = List.of(
            new OperationsReportSection("ENERGY_BASELINE", "能耗基线偏差", "过去5天各楼宇能耗基线偏差"),
            new OperationsReportSection("PARKING_UTILIZATION", "停车利用率", "过去5天各停车区域停车利用率"),
            new OperationsReportSection("ALERT_RISK", "告警风险", "过去5天高风险告警数量"));

    private OperationsDailyReportDefinition() {
    }

    public static List<OperationsReportSection> sections() {
        return SECTIONS;
    }
}
