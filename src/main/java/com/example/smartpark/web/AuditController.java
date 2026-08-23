package com.example.smartpark.web;

import com.example.smartpark.audit.AuditEntry;
import com.example.smartpark.audit.AuditTrail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditTrail auditTrail;

    public AuditController(AuditTrail auditTrail) {
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @GetMapping
    public List<AuditEntry> entries(@RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        return auditTrail.entries();
    }
}
