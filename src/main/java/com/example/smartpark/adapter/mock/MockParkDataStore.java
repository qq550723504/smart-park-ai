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
    // 固定基准时间，保证每次运行测试时都能得到完全一致的数据。
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

    public MockParkDataStore() { reset(); }

    public final void reset() {
        // 重置动态数据后重新装载基础设备、告警、历史记录和知识库。
        devices.clear(); energyReadings.clear(); alerts.clear(); historyByDevice.clear();
        knowledgeDocuments.clear(); workOrdersByWorkflowId.clear(); workOrderSequence.set(0);
        seedDevices(); seedAlerts(); seedHistory(); seedKnowledge();
    }

    public Device getDevice(String deviceId) { return require(devices, deviceId, "device"); }
    public EnergyReading getLatestEnergyReading(String meterId) { return require(energyReadings, meterId, "energy meter"); }
    public Alert getAlert(String alertId) { return require(alerts, alertId, "alert"); }
    public List<Alert> findHistory(String deviceId) { return historyByDevice.getOrDefault(deviceId, List.of()); }

    public List<WorkOrder> findByWorkflowId(String workflowId) {
        WorkOrder workOrder = workOrdersByWorkflowId.get(workflowId);
        return workOrder == null ? List.of() : List.of(workOrder);
    }

    public WorkOrder buildWorkOrder(String workflowId, String alertId, String summary) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(summary, "summary");
        return workOrdersByWorkflowId.computeIfAbsent(workflowId, key -> createWorkOrder(key, alertId, summary));
    }

    public List<KnowledgeDocument> search(String query) {
        // 统一规范化查询文本，兼容中文内容和英文标签。
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return knowledgeDocuments.values().stream()
                .filter(document -> normalizedQuery.isEmpty() || matches(document, normalizedQuery))
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .collect(Collectors.toUnmodifiableList());
    }

    Map<String, Device> devices() { return devices; }
    Map<String, EnergyReading> energyReadings() { return energyReadings; }
    Map<String, Alert> alerts() { return alerts; }
    Map<String, List<Alert>> historyByDevice() { return historyByDevice; }
    Map<String, KnowledgeDocument> knowledgeDocuments() { return knowledgeDocuments; }
    Map<String, WorkOrder> workOrdersByWorkflowId() { return workOrdersByWorkflowId; }
    AtomicInteger workOrderSequence() { return workOrderSequence; }

    private void seedDevices() {
        putDevice(device("DEV-HVAC-001", "A1", "暖通空调送风机组", "HVAC", "ACTIVE", 1));
        putDevice(device("DEV-POWER-001", "A2", "主配电柜", "POWER", "ACTIVE", 2));
        putDevice(device("DEV-ENERGY-001", "A2", "A2 楼宇电能表", "ENERGY_METER", "ACTIVE", 2));
        putDevice(device("DEV-ACCESS-001", "A1", "北门门禁控制器", "ACCESS", "ACTIVE", 3));
        putDevice(device("DEV-PUMP-001", "A2", "地下室排水泵", "PUMP", "ACTIVE", 4));
        putEnergyReading(new EnergyReading("DEV-ENERGY-001", PARK_ID, "A2", ALERT_BASE_TIME.plus(Duration.ofMinutes(6)), 138.0, 100.0, 42.5));
    }

    private void seedAlerts() {
        putAlert(alert("ALT-TEMP-001", "DEV-HVAC-001", "A1", AlertClassification.TEMPERATURE, RiskLevel.LOW,
                "暖通机房温度持续升高", "送风温度已超过舒适区阈值。", ALERT_BASE_TIME, List.of("传感器：温度探头-01", "趋势：持续上升")));
        putAlert(alert("ALT-POWER-001", "DEV-POWER-001", "A2", AlertClassification.POWER, RiskLevel.HIGH,
                "主配电柜电压波动", "主配电柜检测到三相电压不稳定。", ALERT_BASE_TIME.plus(Duration.ofMinutes(3)), List.of("电表：A相", "电表：B相", "电表：C相")));
        putAlert(alert("ALT-ENERGY-001", "DEV-ENERGY-001", "A2", AlertClassification.ENERGY, RiskLevel.HIGH,
                "A2 楼宇能耗异常", "当前时段能耗比学习基线高出 38%。", ALERT_BASE_TIME.plus(Duration.ofMinutes(6)), List.of("电表：当前能耗=138千瓦时", "基线：100千瓦时", "趋势：非工作时段")));
    }

    private void seedHistory() {
        historyByDevice.put("DEV-HVAC-001", List.of(
                alert("ALT-HIST-HVAC-001", "DEV-HVAC-001", "A1", AlertClassification.TEMPERATURE, RiskLevel.LOW, "历史暖通温度预警", "当前告警发生前曾出现轻微温度预警。", HISTORY_BASE_TIME, List.of("日志：暖通温度预警")),
                alert("ALT-HIST-HVAC-002", "DEV-HVAC-001", "A1", AlertClassification.TEMPERATURE, RiskLevel.LOW, "暖通滤网更换提醒", "该机组此前上报过滤网维护提醒。", HISTORY_BASE_TIME.plus(Duration.ofHours(4)), List.of("日志：滤网维护提醒"))));
        historyByDevice.put("DEV-POWER-001", List.of(
                alert("ALT-HIST-POWER-001", "DEV-POWER-001", "A2", AlertClassification.POWER, RiskLevel.HIGH, "主配电柜电压暂降", "近期曾记录到一次短时电压暂降。", HISTORY_BASE_TIME.plus(Duration.ofHours(1)), List.of("日志：电压暂降")),
                alert("ALT-HIST-POWER-002", "DEV-POWER-001", "A2", AlertClassification.POWER, RiskLevel.HIGH, "断路器巡检提醒", "主配电柜此前存在一条断路器巡检记录。", HISTORY_BASE_TIME.plus(Duration.ofHours(5)), List.of("日志：断路器巡检"))));
        historyByDevice.put("DEV-ENERGY-001", List.of(
                alert("ALT-HIST-ENERGY-001", "DEV-ENERGY-001", "A2", AlertClassification.ENERGY, RiskLevel.HIGH, "历史非工作时段能耗峰值", "该电表曾在正常运营时间外上报高能耗。", HISTORY_BASE_TIME.plus(Duration.ofHours(2)), List.of("日志：非工作时段能耗峰值"))));
    }

    private void seedKnowledge() {
        // 知识库使用中文业务内容，便于验证中文检索和诊断提示词。
        putKnowledge(new KnowledgeDocument("KD-OVERHEAT-001", "暖通系统过热处置手册", "暖通送风温度升高时，应先检查滤网、风量和压缩机负载，再决定是否升级处置。", List.of("过热", "暖通", "温度", "overheating", "hvac", "temperature"), KNOWLEDGE_BASE_TIME));
        putKnowledge(new KnowledgeDocument("KD-LEAK-001", "漏水事件处置手册", "应检查水泵房和阀门间是否积水，以及隔离阀是否异常。", List.of("漏水", "水泵", "积水", "leak", "pump", "water"), KNOWLEDGE_BASE_TIME.plus(Duration.ofDays(1))));
        putKnowledge(new KnowledgeDocument("KD-POWER-001", "电力故障应急手册", "立即稳定用电负载、通知设施运维人员，并检查断路器和 UPS 状态。", List.of("电力", "应急", "断路器", "power", "emergency", "breaker"), KNOWLEDGE_BASE_TIME.plus(Duration.ofDays(2))));
        putKnowledge(new KnowledgeDocument("KD-ENERGY-001", "能耗异常处置手册", "能耗高于基线时，应核对运营计划，检查暖通和照明运行时长，并在创建整改工单前校验电表。", List.of("能耗", "用电", "基线", "节能", "energy", "consumption", "baseline", "efficiency"), KNOWLEDGE_BASE_TIME.plus(Duration.ofDays(3))));
    }

    private Device device(String id, String buildingId, String name, String category, String status, int dayOffset) {
        return new Device(id, PARK_ID, buildingId, name, category, status, DEVICE_BASE_TIME.plus(Duration.ofDays(dayOffset)));
    }

    private Alert alert(String id, String deviceId, String buildingId, AlertClassification classification, RiskLevel riskLevel,
                        String summary, String description, Instant occurredAt, List<String> evidence) {
        return new Alert(id, PARK_ID, buildingId, deviceId, classification, riskLevel, summary + " - " + description, occurredAt, evidence);
    }

    private WorkOrder createWorkOrder(String workflowId, String alertId, String summary) {
        // workflowId 作为幂等键，同一工作流重复创建时返回原工单。
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
