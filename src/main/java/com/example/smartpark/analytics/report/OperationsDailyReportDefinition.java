package com.example.smartpark.analytics.report;

import java.util.List;

/** Single source of truth for the customer-facing operations report template. */
public final class OperationsDailyReportDefinition {

    private static final List<OperationsReportSection> SECTIONS = List.of(
            new OperationsReportSection("ENERGY_BASELINE", "能耗基线偏差", "过去5天各楼宇能耗基线偏差",
                    "analytics.v_energy_hourly", "energy_deviation_pct", "%"),
            new OperationsReportSection("PARKING_UTILIZATION", "停车利用率", "过去5天各停车区域停车利用率",
                    "analytics.v_parking_daily", "parking_utilization_pct", "%"),
            new OperationsReportSection("ALERT_RISK", "告警风险", "过去5天高风险告警数量",
                    "analytics.v_alert_fact", "high_risk_alert_count", "条"));

    private OperationsDailyReportDefinition() {
    }

    public static List<OperationsReportSection> sections() {
        return SECTIONS;
    }
}
