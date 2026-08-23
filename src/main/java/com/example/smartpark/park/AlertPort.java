package com.example.smartpark.park;

import com.example.smartpark.model.Alert;

import java.util.List;

public interface AlertPort {
    Alert getAlert(String alertId);

    List<Alert> findHistory(String deviceId);
}
