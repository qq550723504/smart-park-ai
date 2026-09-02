package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;

import java.util.List;

public interface SecurityPort {
    SecurityEvent getEvent(String eventId);

    default List<SecurityEvent> listEvents() {
        return List.of();
    }
}
