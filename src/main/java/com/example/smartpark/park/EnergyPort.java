package com.example.smartpark.park;

import com.example.smartpark.model.EnergyReading;

public interface EnergyPort {
    EnergyReading getLatestEnergyReading(String meterId);
}
