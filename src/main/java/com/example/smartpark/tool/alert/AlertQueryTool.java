package com.example.smartpark.tool.alert;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.port.alert.AlertPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class AlertQueryTool {

    private static final String MOCK_NOTICE = "Mock park data only. Tool outputs do not control real park devices.";

    private final AlertPort alertPort;

    public AlertQueryTool(AlertPort alertPort) {
        this.alertPort = Objects.requireNonNull(alertPort, "alertPort");
    }

    @Tool(name = "lookupAlert", description = "Look up an alert by alertId. Returns the alert or an explicit error result. Never invent an alert.")
    public AlertLookupResult lookupAlert(String alertId) {
        String normalizedAlertId = normalize(alertId);
        if (normalizedAlertId.isEmpty()) {
            return AlertLookupResult.error(normalizedAlertId, "alertId must not be blank");
        }
        try {
            return AlertLookupResult.success(normalizedAlertId, alertPort.getAlert(normalizedAlertId));
        }
        catch (IllegalArgumentException ex) {
            return AlertLookupResult.error(normalizedAlertId, ex.getMessage());
        }
    }

    @Tool(name = "lookupAlertHistory", description = "Look up alert history by deviceId. Returns the known alert history for that device.")
    public AlertHistoryResult lookupAlertHistory(String deviceId) {
        String normalizedDeviceId = normalize(deviceId);
        if (normalizedDeviceId.isEmpty()) {
            return new AlertHistoryResult(normalizedDeviceId, List.of(), "deviceId must not be blank", MOCK_NOTICE);
        }
        return new AlertHistoryResult(normalizedDeviceId, alertPort.findHistory(normalizedDeviceId), null, MOCK_NOTICE);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record AlertLookupResult(String alertId, Alert alert, String error, String notice) {

        public AlertLookupResult {
            alertId = normalize(alertId);
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                alertId = requireText(alertId, "alertId");
                alert = Objects.requireNonNull(alert, "alert");
            }
            else if (alert != null) {
                throw new IllegalArgumentException("error results must not include an alert");
            }
        }

        private static AlertLookupResult success(String alertId, Alert alert) {
            return new AlertLookupResult(alertId, Objects.requireNonNull(alert, "alert"), null, MOCK_NOTICE);
        }

        private static AlertLookupResult error(String alertId, String error) {
            return new AlertLookupResult(alertId, null, requireText(error, "error"), MOCK_NOTICE);
        }
    }

    public record AlertHistoryResult(String deviceId, List<Alert> alerts, String error, String notice) {

        public AlertHistoryResult {
            deviceId = normalize(deviceId);
            alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                deviceId = requireText(deviceId, "deviceId");
            }
        }
    }
}
