package com.example.smartpark.analytics.anomaly;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class OperationsAnomalyDtos {
    private OperationsAnomalyDtos() {}

    public record Window(Instant from, Instant to, String timezone) {}

    public record Summary(long alertCount, long highRiskAlertCount, long offlineDeviceCount,
                          long affectedBuildingCount) {}

    public record Breakdown(String key, long count) {}

    public record BuildingSummary(String buildingId, long alertCount, long highRiskAlertCount,
                                   long offlineDeviceCount, Double energyDeviationPct) {
        public long signalCount() {
            return alertCount + offlineDeviceCount;
        }
    }

    public record Overview(Window window, Instant asOf, Summary summary,
                           Map<String, List<Breakdown>> breakdowns,
                           List<BuildingSummary> buildings,
                           Map<String, String> domainStatus) {
        public Overview {
            breakdowns = Map.copyOf(breakdowns);
            buildings = List.copyOf(buildings);
            domainStatus = Map.copyOf(domainStatus);
        }
    }

    public record Evidence(String buildingId, Window window, Instant asOf,
                           List<AlertAnalyticsReader.AlertReference> alerts,
                           List<DeviceAnalyticsReader.DeviceReference> devices,
                           List<EnergyAnalyticsReader.EnergyReference> energy,
                           Map<String, String> domainStatus) {
        public Evidence {
            alerts = List.copyOf(alerts);
            devices = List.copyOf(devices);
            energy = List.copyOf(energy);
            domainStatus = Map.copyOf(domainStatus);
        }
    }
}
