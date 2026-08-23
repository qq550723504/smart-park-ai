package com.example.smartpark.adapter.mock;

import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.port.energy.EnergyPort;

public final class MockEnergyAdapter implements EnergyPort {
    private final MockParkDataStore dataStore;

    public MockEnergyAdapter(MockParkDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public EnergyReading getLatestEnergyReading(String meterId) {
        return dataStore.getLatestEnergyReading(meterId);
    }
}
