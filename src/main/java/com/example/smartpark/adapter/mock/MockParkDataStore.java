package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.Device;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.model.energy.EnergyReading;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MockParkDataStore {
    private static final String PARK_ID = "PARK-A";
    private static final Instant DEVICE_BASE_TIME = Instant.parse("2026-08-23T00:00:00Z");
    private static final Instant ALERT_BASE_TIME = Instant.parse("2026-08-23T00:15:00Z");
    private static final Instant HISTORY_BASE_TIME = Instant.parse("2026-08-22T23:00:00Z");
    private static final Instant KNOWLEDGE_BASE_TIME = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant WORK_ORDER_BASE_TIME = Instant.parse("2026-08-23T01:00:00Z");

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final Map<String, EnergyReading> energyReadings = new ConcurrentHashMap<>();
    private final Map<String, Alert> alerts = new ConcurrentHashMap<>();
    private final Map<String, List<Alert>> historyByDevice = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeDocument> knowledgeDocuments = new ConcurrentHashMap<>();
    private final Map<String, WorkOrder> workOrdersByWorkflowId = new ConcurrentHashMap<>();
    private final AtomicInteger workOrderSequence = new AtomicInteger();

    MockParkDataStore() { reset(); }

    final void reset() {
        devices.clear(); energyReadings.clear(); alerts.clear(); historyByDevice.clear();
        knowledgeDocuments.clear(); workOrdersByWorkflowId.clear(); workOrderSequence.set(0);
        seedDevices(); seedAlerts(); seedHistory(); seedKnowledge();
    }

    Device getDevice(String deviceId) { return require(devices, deviceId, "device"); }
    EnergyReading getLatestEnergyReading(String meterId) { return require(energyReadings, meterId, "energy meter"); }
    Alert getAlert(String alertId) { return require(alerts, alertId, "alert"); }
    List<Alert> findHistory(String deviceId) { return historyByDevice.getOrDefault(deviceId, List.of()); }

    List<WorkOrder> findByWorkflowId(String workflowId) {
        WorkOrder workOrder = workOrdersByWorkflowId.get(workflowId);
        return workOrder == null ? List.of() : List.of(workOrder);
    }

    WorkOrder buildWorkOrder(String workflowId, String alertId, String summary) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(summary, "summary");
        return workOrdersByWorkflowId.computeIfAbsent(workflowId, key -> createWorkOrder(key, alertId, summary));
    }

    List<KnowledgeDocument> search(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return knowledgeDocuments.values().stream()
                .filter(document -> normalizedQuery.isEmpty() || matches(document, normalizedQuery))
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .collect(Collectors.toUnmodifiableList());
    }

    private void seedDevices() {
        putDevice(device("DEV-HVAC-001", "A1", "HVAC Supply Unit", "HVAC", "ACTIVE", 1));
        putDevice(device("DEV-POWER-001", "A2", "Main Power Panel", "POWER", "ACTIVE", 2));
        putDevice(device("DEV-ENERGY-001", "A2", "Building A2 Energy Meter", "ENERGY_METER", "ACTIVE", 2));
        putDevice(device("DEV-ACCESS-001", "A1", "North Access Controller", "ACCESS", "ACTIVE", 3));
        putDevice(device("DEV-PUMP-001", "A2", "Basement Pump", "PUMP", "ACTIVE", 4));
        putEnergyReading(new EnergyReading("DEV-ENERGY-001", PARK_ID, "A2", ALERT_BASE_TIME.plus(Duration.ofMinutes(6)), 138.0, 100.0, 42.5));
    }

    private void seedAlerts() {
        putAlert(alert("ALT-TEMP-001", "DEV-HVAC-001", "A1", AlertClassification.TEMPERATURE, RiskLevel.LOW,
                "Temperature rising in HVAC room", "Supply air temperature exceeded the comfort threshold.", ALERT_BASE_TIME, List.of("sensor:temp-01", "trend:upward")));
        putAlert(alert("ALT-POWER-001", "DEV-POWER-001", "A2", AlertClassification.POWER, RiskLevel.HIGH,
                "Power fluctuation on main panel", "Voltage instability detected on the main distribution panel.", ALERT_BASE_TIME.plus(Duration.ofMinutes(3)), List.of("meter:phase-a", "meter:phase-b", "meter:phase-c")));
        putAlert(alert("ALT-ENERGY-001", "DEV-ENERGY-001", "A2", AlertClassification.ENERGY, RiskLevel.HIGH,
                "Unexpected energy consumption in building A2", "Current interval consumption is 38 percent above the learned baseline.", ALERT_BASE_TIME.plus(Duration.ofMinutes(6)), List.of("meter:current-kwh=138", "baseline:kwh=100", "trend:after-hours")));
    }

    private void seedHistory() {
        historyByDevice.put("DEV-HVAC-001", List.of(
                alert("ALT-HIST-HVAC-001", "DEV-HVAC-001", "A1", AlertClassification.TEMPERATURE, RiskLevel.LOW, "Prior HVAC temperature warning", "A mild warning was observed before the current alert.", HISTORY_BASE_TIME, List.of("log:hvac-warning")),
                alert("ALT-HIST-HVAC-002", "DEV-HVAC-001", "A1", AlertClassification.TEMPERATURE, RiskLevel.LOW, "HVAC filter replacement reminder", "The HVAC unit previously reported a filter maintenance reminder.", HISTORY_BASE_TIME.plus(Duration.ofHours(4)), List.of("log:filter-reminder"))));
        historyByDevice.put("DEV-POWER-001", List.of(
                alert("ALT-HIST-POWER-001", "DEV-POWER-001", "A2", AlertClassification.POWER, RiskLevel.HIGH, "Voltage sag on main panel", "A short voltage sag was recorded in the recent past.", HISTORY_BASE_TIME.plus(Duration.ofHours(1)), List.of("log:voltage-sag")),
                alert("ALT-HIST-POWER-002", "DEV-POWER-001", "A2", AlertClassification.POWER, RiskLevel.HIGH, "Breaker inspection reminder", "The main panel had a previous breaker inspection note.", HISTORY_BASE_TIME.plus(Duration.ofHours(5)), List.of("log:breaker-note"))));
        historyByDevice.put("DEV-ENERGY-001", List.of(
                alert("ALT-HIST-ENERGY-001", "DEV-ENERGY-001", "A2", AlertClassification.ENERGY, RiskLevel.HIGH, "Previous after-hours energy spike", "The meter previously reported elevated consumption after normal operating hours.", HISTORY_BASE_TIME.plus(Duration.ofHours(2)), List.of("log:after-hours-spike"))));
    }

    private void seedKnowledge() {
        putKnowledge(new KnowledgeDocument("KD-OVERHEAT-001", "HVAC overheating playbook", "When HVAC supply temperatures rise, check filters, airflow, and compressor load before escalating.", List.of("overheating", "hvac", "temperature"), KNOWLEDGE_BASE_TIME));
        putKnowledge(new KnowledgeDocument("KD-LEAK-001", "Water leak response", "Pump rooms and valve closets should be inspected for water accumulation and isolation valve issues.", List.of("leak", "pump", "water"), KNOWLEDGE_BASE_TIME.plus(Duration.ofDays(1))));
        putKnowledge(new KnowledgeDocument("KD-POWER-001", "Power emergency runbook", "Stabilize the electrical load, notify facilities, and inspect breaker and UPS conditions immediately.", List.of("power", "emergency", "breaker"), KNOWLEDGE_BASE_TIME.plus(Duration.ofDays(2))));
        putKnowledge(new KnowledgeDocument("KD-ENERGY-001", "Energy anomaly response playbook", "For consumption above baseline, compare operating schedules, inspect HVAC and lighting runtime, and verify the meter before creating corrective work.", List.of("energy", "consumption", "baseline", "efficiency"), KNOWLEDGE_BASE_TIME.plus(Duration.ofDays(3))));
    }

    private Device device(String id, String buildingId, String name, String category, String status, int dayOffset) {
        return new Device(id, PARK_ID, buildingId, name, category, status, DEVICE_BASE_TIME.plus(Duration.ofDays(dayOffset)));
    }

    private Alert alert(String id, String deviceId, String buildingId, AlertClassification classification, RiskLevel riskLevel,
                        String summary, String description, Instant occurredAt, List<String> evidence) {
        return new Alert(id, PARK_ID, buildingId, deviceId, classification, riskLevel, summary + " - " + description, occurredAt, evidence);
    }

    private WorkOrder createWorkOrder(String workflowId, String alertId, String summary) {
        Alert alert = getAlert(alertId);
        int sequence = workOrderSequence.incrementAndGet();
        Instant createdAt = WORK_ORDER_BASE_TIME.plusSeconds(sequence);
        return new WorkOrder(String.format("WO-%04d", sequence), workflowId, alert.parkId(), alert.buildingId(), alert.deviceId(), alert.id(), summary,
                alert.riskHint(), WorkflowStatus.WAITING_APPROVAL, Optional.empty(), alert.evidence(), createdAt, createdAt);
    }

    private void putDevice(Device device) { devices.put(device.id(), device); }
    private void putEnergyReading(EnergyReading reading) { energyReadings.put(reading.meterId(), reading); }
    private void putAlert(Alert alert) { alerts.put(alert.id(), alert); }
    private void putKnowledge(KnowledgeDocument document) { knowledgeDocuments.put(document.id(), document); }
    private boolean matches(KnowledgeDocument document, String query) { return contains(document.title(), query) || contains(document.content(), query) || document.tags().stream().anyMatch(tag -> contains(tag, query)); }
    private boolean contains(String text, String query) { return text.toLowerCase(Locale.ROOT).contains(query); }

    private static <T> T require(Map<String, T> values, String id, String type) {
        T value = values.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown " + type + ": " + id);
        return value;
    }
}
