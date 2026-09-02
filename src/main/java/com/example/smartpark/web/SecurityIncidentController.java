package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.securityincident.SecurityIncidentQuery;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@ConditionalOnBean(SecurityIncidentService.class)
public class SecurityIncidentController {
    private final SecurityIncidentService service;
    private final AuditTrail auditTrail;

    public SecurityIncidentController(SecurityIncidentService service) {
        this(service, new AuditTrail());
    }

    @Autowired
    public SecurityIncidentController(SecurityIncidentService service, AuditTrail auditTrail) {
        this.service = Objects.requireNonNull(service, "service");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
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
        Map<String, Object> response = SecurityIncidentDtos.detail(service.review(incidentId));
        auditTrail.record(DemoRole.parse(role).name(), "REVIEW_SECURITY_INCIDENT", incidentId, "SUCCESS");
        return response;
    }

    @PostMapping("/api/security/incidents/{incidentId}/handoff")
    public Map<String, Object> handoff(@PathVariable String incidentId,
                                       @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN);
        Map<String, Object> response = SecurityIncidentDtos.detail(service.handoff(incidentId));
        auditTrail.record(DemoRole.parse(role).name(), "HANDOFF_SECURITY_INCIDENT", incidentId, "SUCCESS");
        return response;
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
