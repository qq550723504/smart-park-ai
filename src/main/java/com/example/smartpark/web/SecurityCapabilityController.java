package com.example.smartpark.web;

import com.example.smartpark.port.security.SecurityEventCapabilityRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
public class SecurityCapabilityController {

    private final SecurityEventCapabilityRegistry registry;

    public SecurityCapabilityController(SecurityEventCapabilityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @GetMapping("/api/security/capabilities")
    public Map<String, Object> capabilities(
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("types", registry.capabilities());
        dto.put("dispositionEnabled", registry.dispositionEnabled());
        return dto;
    }
}
