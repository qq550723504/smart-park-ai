package com.example.smartpark.architecture;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.ParkContext;
import com.example.smartpark.model.energy.EnergyReading;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.energy.EnergyQueryTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityPackageTest {

    @Test
    void placesAlertInAlertModelPackage() {
        assertThat(Alert.class.getPackageName()).isEqualTo("com.example.smartpark.model.alert");
    }

    @Test
    void placesParkContextInAlertCapabilityPackage() {
        assertThat(ParkContext.class.getPackageName()).isEqualTo("com.example.smartpark.model.alert");
    }

    @Test
    void placesEnergyReadingInEnergyModelPackage() {
        assertThat(EnergyReading.class.getPackageName()).isEqualTo("com.example.smartpark.model.energy");
    }

    @Test
    void placesEnergyPortInEnergyPortPackage() {
        assertThat(EnergyPort.class.getPackageName()).isEqualTo("com.example.smartpark.port.energy");
    }

    @Test
    void scenarioToolsLiveInCapabilityPackages() {
        assertThat(EnergyQueryTool.class.getPackageName()).isEqualTo("com.example.smartpark.tool.energy");
        assertThat(AlertQueryTool.class.getPackageName()).isEqualTo("com.example.smartpark.tool.alert");
    }
}
