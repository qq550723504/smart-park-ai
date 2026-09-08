package com.example.smartpark.web;

import com.example.smartpark.analytics.anomaly.OperationsAnomalyService;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesDtos;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesQuery;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesService;
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
class OperationsEnergyTimeSeriesControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsMetrics metrics;

    @MockitoBean
    private OperationsAnomalyService anomalyService;

    @MockitoBean
    private EnergyTimeSeriesService energyTimeSeriesService;

    @Test
    void returnsTheStableTimeSeriesContract() throws Exception {
        Instant from = Instant.parse("2026-09-07T00:00:00Z");
        Instant to = Instant.parse("2026-09-07T02:00:00Z");
        when(energyTimeSeriesService.query(any())).thenReturn(new EnergyTimeSeriesDtos.Response(
                "energy_kwh", "kWh", "Asia/Shanghai",
                new EnergyTimeSeriesDtos.Window(from, to, EnergyTimeSeriesQuery.Granularity.HOUR),
                EnergyTimeSeriesDtos.Status.AVAILABLE,
                List.of(new EnergyTimeSeriesDtos.Series("B1", List.of(
                        new EnergyTimeSeriesDtos.Point(from, new BigDecimal("12.5"))), List.of())),
                from, new EnergyTimeSeriesDtos.Source("OPERATIONS_ANALYTICS", "energy_kwh",
                EnergyTimeSeriesDtos.Status.AVAILABLE),
                List.of(new EnergyTimeSeriesDtos.Evidence("B1", 1, 1, from, from))));

        mockMvc.perform(get("/api/operations/energy-time-series")
                        .header("X-Demo-Role", "VIEWER")
                        .param("buildingIds", "B1,B2")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("granularity", "HOUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("energy_kwh"))
                .andExpect(jsonPath("$.unit").value("kWh"))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.series[0].points[0].value").value(12.5))
                .andExpect(jsonPath("$.source.system").value("OPERATIONS_ANALYTICS"));
    }

    @Test
    void keepsValidationAndAuthorizationErrorsFreeOfSensitiveInput() throws Exception {
        when(energyTimeSeriesService.query(any()))
                .thenThrow(new IllegalArgumentException("jdbc://secret-host password=secret"));

        mockMvc.perform(get("/api/operations/energy-time-series")
                        .header("X-Demo-Role", "VIEWER")
                        .param("buildingIds", "B1' OR 1=1 --"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-host"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("1=1"))));

        mockMvc.perform(get("/api/operations/energy-time-series")
                        .header("X-Demo-Role", "CUSTOMER_AGENT")
                        .param("buildingIds", "B1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMalformedTimeAndUnsupportedGranularityWithoutCallingTheDatasource() throws Exception {
        mockMvc.perform(get("/api/operations/energy-time-series")
                        .header("X-Demo-Role", "VIEWER")
                        .param("buildingIds", "B1")
                        .param("from", "jdbc://internal-host")
                        .param("granularity", "MINUTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal-host"))));
    }

    @Test
    void mapsEnergyCapabilityFailureToItsOwnSafeUnavailableContract() throws Exception {
        when(energyTimeSeriesService.query(any())).thenThrow(
                new EnergyTimeSeriesService.EnergyTimeSeriesUnavailableException("jdbc://secret-host/password"));

        mockMvc.perform(get("/api/operations/energy-time-series")
                        .header("X-Demo-Role", "VIEWER")
                        .param("buildingIds", "B1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("能耗时序暂不可用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-host"))));
    }
}
