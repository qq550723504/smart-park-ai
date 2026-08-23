package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;

public interface SecurityPort {
    SecurityEvent getEvent(String eventId);
}
