package com.example.smartpark.port.energy;

import com.example.smartpark.model.energy.EnergyReading;

public interface EnergyPort {
    EnergyReading getLatestEnergyReading(String meterId);
}
