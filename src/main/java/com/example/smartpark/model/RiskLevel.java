package com.example.smartpark.model;

public enum RiskLevel {
    LOW,
    HIGH;

    public boolean isHighRisk() {
        return this == HIGH;
    }
}
