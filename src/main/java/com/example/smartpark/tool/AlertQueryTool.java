package com.example.smartpark.tool;

import com.example.smartpark.model.Alert;
import com.example.smartpark.park.AlertPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class AlertQueryTool {

    private static final String MOCK_NOTICE = "Mock park data only. Tool outputs do not control real park devices.";

    private final AlertPort alertPort;

    public AlertQueryTool(AlertPort alertPort) {
        this.alertPort = Objects.requireNonNull(alertPort, "alertPort");
    }

    @Tool(name = "lookupAlert", description = "Look up an alert by alertId. Returns the alert or an explicit error result. Never invent an alert.")
    public AlertLookupResult lookupAlert(String alertId) {
        String normalizedAlertId = requireText(alertId, "alertId");
        try {
            return AlertLookupResult.success(normalizedAlertId, alertPort.getAlert(normalizedAlertId));
        }
        catch (IllegalArgumentException ex) {
            return AlertLookupResult.error(normalizedAlertId, ex.getMessage());
        }
    }

    @Tool(name = "lookupAlertHistory", description = "Look up alert history by deviceId. Returns the known alert history for that device.")
    public AlertHistoryResult lookupAlertHistory(String deviceId) {
        String normalizedDeviceId = requireText(deviceId, "deviceId");
        return new AlertHistoryResult(normalizedDeviceId, alertPort.findHistory(normalizedDeviceId), null, MOCK_NOTICE);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public record AlertLookupResult(String alertId, Alert alert, String error, String notice) {

        public AlertLookupResult {
            alertId = requireText(alertId, "alertId");
            notice = requireText(notice, "notice");
            if ((alert == null) == (error == null || error.isBlank())) {
                throw new IllegalArgumentException("exactly one of alert or error must be present");
            }
            if (error != null) {
                error = error.trim();
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
            deviceId = requireText(deviceId, "deviceId");
            alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
            notice = requireText(notice, "notice");
            if (error != null) {
                error = error.trim();
            }
        }
    }
}
