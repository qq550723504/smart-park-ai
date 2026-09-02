package com.example.smartpark.web;

import com.example.smartpark.securityincident.SecurityIncidentQuery;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

@RestController
public class SecurityIncidentController {
    private final SecurityIncidentService service;

    public SecurityIncidentController(SecurityIncidentService service) {
        this.service = service;
    }

    @GetMapping("/api/security/incidents")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN);
        return SecurityIncidentDtos.page(service.list(new SecurityIncidentQuery(parseStatus(status), limit)));
    }

    @GetMapping("/api/security/incidents/{incidentId}")
    public Map<String, Object> get(@PathVariable String incidentId,
                                   @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN);
        return SecurityIncidentDtos.detail(service.get(incidentId));
    }

    @PostMapping("/api/security/incidents/{incidentId}/review")
    public Map<String, Object> review(@PathVariable String incidentId,
                                      @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN);
        return SecurityIncidentDtos.detail(service.review(incidentId));
    }

    @PostMapping("/api/security/incidents/{incidentId}/handoff")
    public Map<String, Object> handoff(@PathVariable String incidentId,
                                       @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN);
        return SecurityIncidentDtos.detail(service.handoff(incidentId));
    }

    private static SecurityIncidentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return SecurityIncidentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("status is not supported");
        }
    }
}
