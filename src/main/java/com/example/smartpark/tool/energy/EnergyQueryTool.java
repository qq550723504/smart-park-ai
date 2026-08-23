package com.example.smartpark.tool.energy;

import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.port.energy.EnergyPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class EnergyQueryTool {

    private static final String MOCK_NOTICE = "Mock park data only. Energy readings do not control real park equipment.";

    private final EnergyPort energyPort;

    public EnergyQueryTool(EnergyPort energyPort) {
        this.energyPort = Objects.requireNonNull(energyPort, "energyPort");
    }

    @Tool(name = "lookupEnergyConsumption", description = "Look up the latest energy consumption and compare it with the meter baseline. Returns an explicit error when the meter is unknown. Never invent energy data.")
    public EnergyLookupResult lookupEnergyConsumption(String meterId) {
        String normalizedMeterId = normalize(meterId);
        if (normalizedMeterId.isEmpty()) {
            return EnergyLookupResult.error(normalizedMeterId, "meterId must not be blank");
        }
        try {
            return EnergyLookupResult.success(normalizedMeterId, energyPort.getLatestEnergyReading(normalizedMeterId));
        }
        catch (IllegalArgumentException ex) {
            return EnergyLookupResult.error(normalizedMeterId, ex.getMessage());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    public record EnergyLookupResult(
            String meterId,
            EnergyReading reading,
            String error,
            String notice) {

        public EnergyLookupResult {
            meterId = normalize(meterId);
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                meterId = requireText(meterId, "meterId");
                reading = Objects.requireNonNull(reading, "reading");
            }
            else if (reading != null) {
                throw new IllegalArgumentException("error results must not include a reading");
            }
        }

        private static EnergyLookupResult success(String meterId, EnergyReading reading) {
            return new EnergyLookupResult(meterId, Objects.requireNonNull(reading, "reading"), null, MOCK_NOTICE);
        }

        private static EnergyLookupResult error(String meterId, String error) {
            return new EnergyLookupResult(meterId, null, requireText(error, "error"), MOCK_NOTICE);
        }
    }
}
