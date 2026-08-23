package com.example.smartpark.model.common;

public enum RiskLevel {
    LOW,
    HIGH;

    public boolean isHighRisk() {
        return this == HIGH;
    }
}
