package com.example.smartpark.model.alert;

import com.example.smartpark.model.common.Device;
import com.example.smartpark.model.common.WorkOrder;

import java.util.List;
import java.util.Objects;

public record ParkContext(
        String parkId,
        String buildingId,
        Device device,
        List<Alert> alertHistory,
        List<WorkOrder> workOrders) {

    public ParkContext {
        parkId = Objects.requireNonNull(parkId, "parkId");
        buildingId = Objects.requireNonNull(buildingId, "buildingId");
        device = Objects.requireNonNull(device, "device");
        alertHistory = List.copyOf(Objects.requireNonNull(alertHistory, "alertHistory"));
        workOrders = List.copyOf(Objects.requireNonNull(workOrders, "workOrders"));
    }
}
