package com.example.smartpark.analytics.telemetry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Closed registry for telemetry types. Request input never becomes SQL. */
public final class TelemetryCatalog {
    public enum CapabilityStatus { ADAPTED, NOT_READY }

    public record Threshold(BigDecimal attentionAbove, BigDecimal criticalAbove, String source) {
        public Threshold {
            Objects.requireNonNull(attentionAbove, "attentionAbove");
            Objects.requireNonNull(criticalAbove, "criticalAbove");
            if (attentionAbove.compareTo(criticalAbove) >= 0) {
                throw new IllegalArgumentException("attention threshold must be below critical threshold");
            }
            if (source == null || source.isBlank()) throw new IllegalArgumentException("threshold source is required");
        }
    }

    public record Definition(String telemetryType, String unit, String sourceSystem, String sourceView,
                             Set<String> allowedDeviceTypes, Threshold threshold,
                             CapabilityStatus capabilityStatus, String datasetKind) {
        public Definition {
            telemetryType = requireText(telemetryType, "telemetryType");
            unit = requireText(unit, "unit");
            sourceSystem = requireText(sourceSystem, "sourceSystem");
            allowedDeviceTypes = Set.copyOf(Objects.requireNonNull(allowedDeviceTypes, "allowedDeviceTypes"));
            capabilityStatus = Objects.requireNonNull(capabilityStatus, "capabilityStatus");
            datasetKind = requireText(datasetKind, "datasetKind");
            if (capabilityStatus == CapabilityStatus.ADAPTED && (sourceView == null || sourceView.isBlank())) {
                throw new IllegalArgumentException("adapted telemetry requires a source view");
            }
        }
    }

    private final List<Definition> definitions = List.of(
            new Definition("TEMPERATURE", "°C", "OPERATIONS_ANALYTICS_DEMO",
                    "analytics.v_device_telemetry_hourly", Set.of("HVAC"),
                    new Threshold(new BigDecimal("28.0"), new BigDecimal("35.0"),
                            "DEMO_POLICY:HVAC_SUPPLY_TEMPERATURE_V1"),
                    CapabilityStatus.ADAPTED, "DEMO"),
            new Definition("VIBRATION", "mm/s", "NOT_CONNECTED", null,
                    Set.of("HVAC", "PUMP"), null, CapabilityStatus.NOT_READY, "NONE"));

    public Definition resolve(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("telemetryType is required");
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return definitions.stream().filter(item -> item.telemetryType().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown telemetryType"));
    }

    public Optional<Definition> availableForDeviceType(String deviceType) {
        if (deviceType == null) return Optional.empty();
        return definitions.stream()
                .filter(item -> item.capabilityStatus() == CapabilityStatus.ADAPTED)
                .filter(item -> item.allowedDeviceTypes().contains(deviceType.toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    public List<Definition> all() {
        return definitions;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
