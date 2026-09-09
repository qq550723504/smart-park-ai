package com.example.smartpark.web;

import com.example.smartpark.analytics.anomaly.OperationsAnomalyService;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesService;
import com.example.smartpark.analytics.health.DeviceHealthDtos;
import com.example.smartpark.analytics.health.DeviceHealthService;
import com.example.smartpark.analytics.telemetry.DeviceTelemetryDtos;
import com.example.smartpark.analytics.telemetry.DeviceTelemetryQuery;
import com.example.smartpark.analytics.telemetry.DeviceTelemetryService;
import com.example.smartpark.operations.OperationsMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationsController.class)
@Import(ApiExceptionHandler.class)
class OperationsDeviceTelemetryControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean OperationsMetrics metrics;
    @MockitoBean OperationsAnomalyService anomalyService;
    @MockitoBean EnergyTimeSeriesService energyTimeSeriesService;
    @MockitoBean DeviceTelemetryService telemetryService;
    @MockitoBean DeviceHealthService healthService;

    @Test
    void exposesStableTelemetryAndHealthContractsToReadOnlyOperationsRoles() throws Exception {
        Instant from = Instant.parse("2026-09-08T08:00:00Z");
        Instant to = Instant.parse("2026-09-08T10:00:00Z");
        when(telemetryService.query(any())).thenReturn(new DeviceTelemetryDtos.Response(
                "TEMPERATURE", "°C", "Asia/Shanghai",
                new DeviceTelemetryDtos.Window(from, to, DeviceTelemetryQuery.Granularity.HOUR),
                DeviceTelemetryDtos.Status.PARTIAL,
                List.of(new DeviceTelemetryDtos.Series("AC-B1-07", "B1", "HVAC",
                        List.of(new DeviceTelemetryDtos.Point(from, new BigDecimal("30.5"), "GOOD")),
                        List.of(from.plusSeconds(3600)), DeviceTelemetryDtos.Freshness.FRESH)),
                new DeviceTelemetryDtos.Threshold(new BigDecimal("28"), new BigDecimal("35"),
                        "DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1"), from,
                new DeviceTelemetryDtos.Source("OPERATIONS_ANALYTICS_DEMO", "TEMPERATURE",
                        DeviceTelemetryDtos.Status.PARTIAL, "DEMO"),
                List.of(new DeviceTelemetryDtos.Evidence("AC-B1-07", 2, 1, from, from))));
        when(healthService.assess("AC-B1-07")).thenReturn(new DeviceHealthDtos.Response(
                "AC-B1-07", "B1", "HVAC", DeviceHealthDtos.HealthStatus.DEGRADED,
                DeviceHealthDtos.Availability.PARTIAL, List.of("温度持续超过已登记阈值"),
                List.of(new DeviceHealthDtos.Evidence("TELEMETRY_THRESHOLD", "telemetry:TEMPERATURE:x",
                        from, "DEMO_POLICY")),
                List.of(new DeviceHealthDtos.Source("OPERATIONS_ANALYTICS_DEMO",
                        DeviceHealthDtos.Availability.PARTIAL)), from));

        mockMvc.perform(get("/api/operations/device-telemetry")
                        .header("X-Demo-Role", "OPERATOR")
                        .param("telemetryType", "TEMPERATURE")
                        .param("deviceIds", "AC-B1-07")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("granularity", "HOUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telemetryType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.unit").value("°C"))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.series[0].missingTimestamps[0]").value("2026-09-08T09:00:00Z"))
                .andExpect(jsonPath("$.source.datasetKind").value("DEMO"));

        mockMvc.perform(get("/api/operations/device-health/AC-B1-07")
                        .header("X-Demo-Role", "APPROVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.reasons[0]").value("温度持续超过已登记阈值"))
                .andExpect(jsonPath("$.healthScore").doesNotExist());
    }

    @Test
    void rejectsUnauthorizedMalformedAndSensitiveInputsWithoutLeakingThem() throws Exception {
        mockMvc.perform(get("/api/operations/device-telemetry")
                        .header("X-Demo-Role", "CUSTOMER_AGENT")
                        .param("telemetryType", "TEMPERATURE")
                        .param("deviceIds", "AC-B1-07"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/operations/device-telemetry")
                        .header("X-Demo-Role", "VIEWER")
                        .param("telemetryType", "TEMPERATURE")
                        .param("deviceIds", "AC-B1-07")
                        .param("from", "jdbc://internal-host"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal-host"))));

        when(telemetryService.query(any()))
                .thenThrow(new IllegalArgumentException("jdbc://secret-host password=secret"));
        mockMvc.perform(get("/api/operations/device-telemetry")
                        .header("X-Demo-Role", "VIEWER")
                        .param("telemetryType", "TEMPERATURE")
                        .param("deviceIds", "AC-B1-07"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-host"))));
    }
}
