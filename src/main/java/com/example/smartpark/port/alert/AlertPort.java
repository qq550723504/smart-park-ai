package com.example.smartpark.port.alert;

import com.example.smartpark.model.alert.Alert;

import java.util.List;

public interface AlertPort {
    Alert getAlert(String alertId);

    List<Alert> listActive();

    List<Alert> findHistory(String deviceId);
}
