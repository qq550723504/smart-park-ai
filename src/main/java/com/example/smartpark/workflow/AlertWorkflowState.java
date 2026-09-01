package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.ParkContext;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkflowStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AlertWorkflowState {

    public static final String WORKFLOW_ID = "workflowId";
    public static final String ALERT_ID = "alertId";
    public static final String ALERT = "alert";
    public static final String CLASSIFICATION = "classification";
    public static final String PARK_CONTEXT = "parkContext";
    public static final String RETRIEVED_DOCUMENTS = "retrievedDocuments";
    public static final String DIAGNOSIS = "diagnosis";
    public static final String RISK_LEVEL = "riskLevel";
    public static final String APPROVAL = "approval";
    public static final String WORK_ORDER = "workOrder";
    public static final String STATUS = "status";
    public static final String ERRORS = "errors";
    public static final String EVENT_SEQUENCE = "eventSequence";
    public static final String ROUTE = "route";
    public static final String RESULT_SUMMARY = "resultSummary";
    public static final String SCENARIO_ANALYSIS = "scenarioAnalysis";
    public static final String RISK_REASONS = "riskReasons";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_AT = "updatedAt";

    private static final List<String> REQUIRED_KEYS = List.of(
            WORKFLOW_ID,
            ALERT_ID,
            ALERT,
            CLASSIFICATION,
            PARK_CONTEXT,
            RETRIEVED_DOCUMENTS,
            DIAGNOSIS,
            RISK_LEVEL,
            APPROVAL,
            WORK_ORDER,
            STATUS,
            ERRORS,
            EVENT_SEQUENCE,
            CREATED_AT,
            UPDATED_AT);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Map<String, Object> data;

    private AlertWorkflowState(Map<String, Object> data) {
        this.data = new LinkedHashMap<>(data);
    }

    public static AlertWorkflowState initial(String workflowId, String alertId, Instant createdAt) {
        Objects.requireNonNull(createdAt, "createdAt");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(WORKFLOW_ID, workflowId);
        data.put(ALERT_ID, alertId);
        data.put(RETRIEVED_DOCUMENTS, List.of());
        data.put(STATUS, serializable(WorkflowStatus.RUNNING));
        data.put(ERRORS, List.of());
        data.put(EVENT_SEQUENCE, 0L);
        data.put(CREATED_AT, createdAt.toString());
        data.put(UPDATED_AT, createdAt.toString());
        return new AlertWorkflowState(data);
    }

    public static AlertWorkflowState from(OverAllState state) {
        return new AlertWorkflowState(state.data());
    }

    public static Map<String, KeyStrategy> keyStrategies() {
        Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
        REQUIRED_KEYS.forEach(key -> strategies.put(key, new ReplaceStrategy()));
        strategies.put(ROUTE, new ReplaceStrategy());
        strategies.put(RESULT_SUMMARY, new ReplaceStrategy());
        strategies.put(SCENARIO_ANALYSIS, new ReplaceStrategy());
        strategies.put(RISK_REASONS, new ReplaceStrategy());
        return strategies;
    }

    public static Object serializable(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof java.time.temporal.TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            map.forEach((key, item) -> serialized.put(String.valueOf(key), serializable(item)));
            return serialized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> serialized = new ArrayList<>();
            iterable.forEach(item -> serialized.add(serializable(item)));
            return List.copyOf(serialized);
        }
        Map<String, Object> converted = OBJECT_MAPPER.convertValue(
                value,
                new TypeReference<Map<String, Object>>() { });
        return serializable(converted);
    }

    public Map<String, Object> data() {
        return Collections.unmodifiableMap(data);
    }

    public Map<String, Object> snapshotPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        REQUIRED_KEYS.forEach(key -> payload.put(key, data.get(key)));
        if (data.containsKey(ROUTE)) {
            payload.put(ROUTE, data.get(ROUTE));
        }
        if (data.containsKey(RESULT_SUMMARY)) {
            payload.put(RESULT_SUMMARY, data.get(RESULT_SUMMARY));
        }
        if (data.containsKey(SCENARIO_ANALYSIS)) {
            payload.put(SCENARIO_ANALYSIS, data.get(SCENARIO_ANALYSIS));
        }
        if (data.containsKey(RISK_REASONS)) {
            payload.put(RISK_REASONS, data.get(RISK_REASONS));
        }
        return payload;
    }

    public String workflowId() {
        return required(WORKFLOW_ID, String.class);
    }

    public String alertId() {
        return required(ALERT_ID, String.class);
    }

    public Alert alert() {
        return required(ALERT, Alert.class);
    }

    public AlertTriageAgent.AlertClassificationResult classification() {
        return required(CLASSIFICATION, AlertTriageAgent.AlertClassificationResult.class);
    }

    public ParkContext parkContext() {
        return required(PARK_CONTEXT, ParkContext.class);
    }

    public List<KnowledgeDocument> retrievedDocuments() {
        Object value = data.get(RETRIEVED_DOCUMENTS);
        if (value == null) {
            return List.of();
        }
        return List.copyOf(OBJECT_MAPPER.convertValue(value, new TypeReference<List<KnowledgeDocument>>() { }));
    }

    public Optional<Diagnosis> diagnosis() {
        return optional(DIAGNOSIS, Diagnosis.class);
    }

    public Optional<RiskLevel> riskLevel() {
        return optional(RISK_LEVEL, RiskLevel.class);
    }

    public Optional<ApprovalDecision> approval() {
        return optional(APPROVAL, ApprovalDecision.class);
    }

    public Optional<WorkOrder> workOrder() {
        return optional(WORK_ORDER, WorkOrder.class);
    }

    public WorkflowStatus status() {
        return optional(STATUS, WorkflowStatus.class).orElse(WorkflowStatus.RUNNING);
    }

    public List<String> errors() {
        Object value = data.get(ERRORS);
        if (value == null) {
            return List.of();
        }
        List<?> raw = OBJECT_MAPPER.convertValue(value, List.class);
        List<String> errors = new ArrayList<>();
        raw.forEach(item -> errors.add(String.valueOf(item)));
        return List.copyOf(errors);
    }

    public Route route() {
        return required(ROUTE, Route.class);
    }

    public long eventSequence() {
        Object value = data.get(EVENT_SEQUENCE);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private <T> T required(String key, Class<T> type) {
        return optional(key, type).orElseThrow(() -> new IllegalStateException("Missing workflow state: " + key));
    }

    private <T> Optional<T> optional(String key, Class<T> type) {
        Object value = data.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        if (type.isEnum() && value instanceof String text) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            T enumValue = (T) Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), text);
            return Optional.of(enumValue);
        }
        return Optional.of(OBJECT_MAPPER.convertValue(value, type));
    }
}
