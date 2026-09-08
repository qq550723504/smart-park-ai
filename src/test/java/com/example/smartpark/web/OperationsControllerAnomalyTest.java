package com.example.smartpark.web;

import com.example.smartpark.analytics.anomaly.AlertAnalyticsReader;
import com.example.smartpark.analytics.anomaly.DeviceAnalyticsReader;
import com.example.smartpark.analytics.anomaly.EnergyAnalyticsReader;
import com.example.smartpark.analytics.anomaly.OperationsAnomalyService;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesDtos;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesReader;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesService;
import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.operations.OperationsMetrics;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OperationsControllerAnomalyTest {
    @Test
    void exposesTheReadOnlyAnomalyEndpointPaths() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/smartpark/web/OperationsController.java"));

        assertThat(source).contains("/anomaly-overview", "/anomaly-evidence/{buildingId}", "/energy-time-series");
    }

    @Test
    void parsesTheOverviewWindowAndAcceptsReadOnlyViewerRole() {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        OperationsController controller = new OperationsController(metrics(),
                new OperationsAnomalyService(query -> emptyAlerts(), query -> emptyDevices(), query -> emptyEnergy(),
                        Clock.fixed(now, ZoneOffset.UTC)));

        var overview = controller.anomalyOverview("VIEWER", "2026-09-01T00:00:00Z", "2026-09-03T00:00:00Z",
                null, null, null, null, null);

        assertThat(overview.window().from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(overview.window().to()).isEqualTo(Instant.parse("2026-09-03T00:00:00Z"));
    }

    @Test
    void rejectsMalformedOverviewWindow() {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        OperationsController controller = new OperationsController(metrics(),
                new OperationsAnomalyService(query -> emptyAlerts(), query -> emptyDevices(), query -> emptyEnergy(),
                        Clock.fixed(now, ZoneOffset.UTC)));

        assertThatThrownBy(() -> controller.anomalyOverview("VIEWER", "not-a-time", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesEnergyTimeSeriesToReadOnlyRolesWithParsedWindowAndGranularity() {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        EnergyTimeSeriesReader reader = (request, metric) -> EnergyTimeSeriesReader.Snapshot.available(List.of(
                new EnergyTimeSeriesReader.Row("B1", request.from(), java.math.BigDecimal.TEN, request.from())), false);
        EnergyTimeSeriesService timeSeries = new EnergyTimeSeriesService(new MetricCatalog(), reader,
                Clock.fixed(now, ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"));
        OperationsController controller = new OperationsController(metrics(),
                new OperationsAnomalyService(query -> emptyAlerts(), query -> emptyDevices(), query -> emptyEnergy(),
                        Clock.fixed(now, ZoneOffset.UTC)), timeSeries);

        var response = controller.energyTimeSeries("VIEWER", "energy_kwh", List.of("B1"),
                "2026-09-03T10:00:00Z", "2026-09-03T11:00:00Z", "hour");

        assertThat(response.status()).isEqualTo(EnergyTimeSeriesDtos.Status.AVAILABLE);
        assertThat(response.window().granularity().name()).isEqualTo("HOUR");
        assertThat(response.series()).singleElement().extracting(EnergyTimeSeriesDtos.Series::buildingId)
                .isEqualTo("B1");
    }

    @Test
    void rejectsUnsupportedGranularityAndUnauthorizedEnergyReads() {
        Instant now = Instant.parse("2026-09-03T12:00:00Z");
        EnergyTimeSeriesService timeSeries = new EnergyTimeSeriesService(new MetricCatalog(),
                (request, metric) -> EnergyTimeSeriesReader.Snapshot.available(List.of(), false),
                Clock.fixed(now, ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"));
        OperationsController controller = new OperationsController(metrics(),
                new OperationsAnomalyService(query -> emptyAlerts(), query -> emptyDevices(), query -> emptyEnergy(),
                        Clock.fixed(now, ZoneOffset.UTC)), timeSeries);

        assertThatThrownBy(() -> controller.energyTimeSeries("VIEWER", "energy_kwh", List.of("B1"),
                null, null, "MINUTE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.energyTimeSeries("CUSTOMER_AGENT", "energy_kwh", List.of("B1"),
                null, null, "HOUR")).isInstanceOf(ForbiddenOperationException.class);
    }

    private static OperationsMetrics metrics() {
        return new OperationsMetrics(null, mock(CustomerServiceWorkflow.class), new AuditTrail());
    }

    private static AlertAnalyticsReader.Snapshot emptyAlerts() {
        return new AlertAnalyticsReader.Snapshot(0, 0, List.of(), List.of(), List.of(), List.of(), true, null);
    }

    private static DeviceAnalyticsReader.Snapshot emptyDevices() {
        return new DeviceAnalyticsReader.Snapshot(0, List.of(), List.of(), Instant.parse("2026-09-03T02:00:00Z"), true, null);
    }

    private static EnergyAnalyticsReader.Snapshot emptyEnergy() {
        return new EnergyAnalyticsReader.Snapshot(List.of(), true, null);
    }
}
