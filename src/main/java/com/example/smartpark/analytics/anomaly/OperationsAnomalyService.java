package com.example.smartpark.analytics.anomaly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OperationsAnomalyService {
    private static final Duration DEFAULT_MAX_WINDOW = Duration.ofDays(31);
    private final AlertAnalyticsReader alerts;
    private final DeviceAnalyticsReader devices;
    private final EnergyAnalyticsReader energy;
    private final Clock clock;
    private final Duration maxWindow;

    public OperationsAnomalyService(AlertAnalyticsReader alerts, DeviceAnalyticsReader devices,
                                    EnergyAnalyticsReader energy, Clock clock) {
        this(alerts, devices, energy, clock, DEFAULT_MAX_WINDOW);
    }

    public OperationsAnomalyService(AlertAnalyticsReader alerts, DeviceAnalyticsReader devices,
                                    EnergyAnalyticsReader energy, Clock clock, Duration maxWindow) {
        this.alerts = java.util.Objects.requireNonNull(alerts, "alerts");
        this.devices = java.util.Objects.requireNonNull(devices, "devices");
        this.energy = java.util.Objects.requireNonNull(energy, "energy");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.maxWindow = java.util.Objects.requireNonNull(maxWindow, "maxWindow");
    }

    public OperationsAnomalyDtos.Overview overview(OperationsAnomalyQuery input) {
        OperationsAnomalyQuery query = normalized(input);
        AlertAnalyticsReader.Snapshot alertSnapshot = alerts.read(query);
        DeviceAnalyticsReader.Snapshot deviceSnapshot = devices.read(query);
        EnergyAnalyticsReader.Snapshot energySnapshot = energy.read(query);
        if (!alertSnapshot.available() && !deviceSnapshot.available() && !energySnapshot.available()) {
            throw new AnomalyOverviewUnavailableException("无法构造运营异常总览");
        }

        Map<String, BuildingAccumulator> byBuilding = new LinkedHashMap<>();
        alertSnapshot.buildings().forEach(building -> byBuilding
                .computeIfAbsent(building.buildingId(), ignored -> new BuildingAccumulator(building.buildingId()))
                .withAlerts(building.alertCount(), building.highRiskAlertCount()));
        deviceSnapshot.buildings().forEach(building -> byBuilding
                .computeIfAbsent(building.buildingId(), ignored -> new BuildingAccumulator(building.buildingId()))
                .withOfflineDevices(building.offlineDeviceCount()));
        energySnapshot.buildings().stream()
                .filter(building -> hasEnergyAnomalySignal(building.deviationPct()))
                .forEach(building -> byBuilding
                        .computeIfAbsent(building.buildingId(), ignored -> new BuildingAccumulator(building.buildingId()))
                        .withEnergyDeviation(building.deviationPct()));

        List<OperationsAnomalyDtos.BuildingSummary> buildings = byBuilding.values().stream()
                .map(BuildingAccumulator::toDto)
                .sorted(Comparator.comparingLong(OperationsAnomalyDtos.BuildingSummary::signalCount).reversed()
                        .thenComparing(OperationsAnomalyDtos.BuildingSummary::buildingId))
                .toList();
        Map<String, List<OperationsAnomalyDtos.Breakdown>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("riskLevels", convert(alertSnapshot.riskLevels()));
        breakdowns.put("categories", convert(alertSnapshot.categories()));
        breakdowns.put("statuses", convert(alertSnapshot.statuses()));
        breakdowns.put("deviceTypes", convert(deviceSnapshot.deviceTypes()));
        return new OperationsAnomalyDtos.Overview(
                new OperationsAnomalyDtos.Window(query.from(), query.to(), "Asia/Shanghai"),
                deviceSnapshot.asOf(),
                new OperationsAnomalyDtos.Summary(alertSnapshot.alertCount(), alertSnapshot.highRiskAlertCount(),
                        deviceSnapshot.offlineDeviceCount(), buildings.size()),
                breakdowns, buildings, Map.of(
                        "alerts", status(alertSnapshot.available(), alertSnapshot.failureCode()),
                        "devices", status(deviceSnapshot.available(), deviceSnapshot.failureCode()),
                        "energy", status(energySnapshot.available(), energySnapshot.failureCode())));
    }

    public OperationsAnomalyDtos.Evidence evidence(String buildingId, OperationsAnomalyQuery input) {
        if (buildingId == null || buildingId.isBlank()) throw new IllegalArgumentException("buildingId must not be blank");
        OperationsAnomalyQuery query = normalized(input);
        OperationsAnomalyQuery scoped = new OperationsAnomalyQuery(query.from(), query.to(), buildingId,
                query.riskLevel(), query.category(), query.status(), query.deviceType());
        AlertAnalyticsReader.Snapshot alertSnapshot = alerts.read(scoped);
        DeviceAnalyticsReader.Snapshot deviceSnapshot = devices.read(scoped);
        EnergyAnalyticsReader.Snapshot energySnapshot = energy.read(scoped);
        EvidenceResult<AlertAnalyticsReader.AlertReference> alertEvidence = alerts.evidence(buildingId, scoped);
        EvidenceResult<DeviceAnalyticsReader.DeviceReference> deviceEvidence = devices.evidence(buildingId, scoped);
        EvidenceResult<EnergyAnalyticsReader.EnergyReference> energyEvidence = energy.evidence(buildingId, scoped);
        return new OperationsAnomalyDtos.Evidence(buildingId,
                new OperationsAnomalyDtos.Window(query.from(), query.to(), "Asia/Shanghai"),
                deviceSnapshot.asOf(), alertEvidence.items(), deviceEvidence.items(), energyEvidence.items(),
                Map.of("alerts", evidenceStatus(alertSnapshot.available(), alertSnapshot.failureCode(), alertEvidence),
                        "devices", evidenceStatus(deviceSnapshot.available(), deviceSnapshot.failureCode(), deviceEvidence),
                        "energy", evidenceStatus(energySnapshot.available(), energySnapshot.failureCode(), energyEvidence)));
    }

    private OperationsAnomalyQuery normalized(OperationsAnomalyQuery input) {
        OperationsAnomalyQuery query = (input == null ? new OperationsAnomalyQuery(null, null, null, null, null, null, null) : input)
                .normalized(clock.instant());
        query.validate(maxWindow);
        return query;
    }

    private static List<OperationsAnomalyDtos.Breakdown> convert(List<AlertAnalyticsReader.Breakdown> values) {
        return values.stream().map(value -> new OperationsAnomalyDtos.Breakdown(value.key(), value.count())).toList();
    }

    private static String status(boolean available, String failureCode) {
        if (!available) return "UNAVAILABLE";
        return failureCode == null ? "OK" : "PARTIAL";
    }

    private static String evidenceStatus(boolean snapshotAvailable, String snapshotFailureCode,
                                         EvidenceResult<?> evidence) {
        if (!snapshotAvailable || !evidence.available()) return "UNAVAILABLE";
        return snapshotFailureCode == null && evidence.failureCode() == null ? "OK" : "PARTIAL";
    }

    private static boolean hasEnergyAnomalySignal(Double deviation) {
        return deviation != null && deviation != 0.0;
    }

    public static final class AnomalyOverviewUnavailableException extends RuntimeException {
        public AnomalyOverviewUnavailableException(String message) {
            super(message);
        }
    }

    private static final class BuildingAccumulator {
        private final String buildingId;
        private long alertCount;
        private long highRiskAlertCount;
        private long offlineDeviceCount;
        private Double energyDeviationPct;

        private BuildingAccumulator(String buildingId) { this.buildingId = buildingId; }
        private BuildingAccumulator withAlerts(long alerts, long highRisk) { alertCount += alerts; highRiskAlertCount += highRisk; return this; }
        private BuildingAccumulator withOfflineDevices(long offline) { offlineDeviceCount += offline; return this; }
        private BuildingAccumulator withEnergyDeviation(Double deviation) { energyDeviationPct = deviation; return this; }
        private OperationsAnomalyDtos.BuildingSummary toDto() { return new OperationsAnomalyDtos.BuildingSummary(buildingId, alertCount, highRiskAlertCount, offlineDeviceCount, energyDeviationPct); }
    }
}
