package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityEvent;

import java.util.List;

/**
 * Boundary for a security source (camera/video analytics, access control or an
 * existing feed). Adapters map vendor-private payloads into unified domain
 * events; the UI must never depend on vendor fields.
 */
public interface SecuritySourceAdapter {

    SecuritySourceDescriptor descriptor();

    List<SecurityEvent> readEvents();
}
