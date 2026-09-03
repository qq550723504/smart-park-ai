package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;

import java.util.List;

public interface SecurityEventReader extends SecurityPort {
    List<SecurityEvent> listEvents();
}
