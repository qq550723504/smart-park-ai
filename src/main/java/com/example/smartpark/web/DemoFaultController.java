package com.example.smartpark.web;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.demo.DemoFaultInjector;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/demo/faults")
public class DemoFaultController {
    private final DemoFaultInjector injector;
    private final AuditTrail auditTrail;

    public DemoFaultController(DemoFaultInjector injector, AuditTrail auditTrail) {
        this.injector = Objects.requireNonNull(injector, "injector");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
    }

    @PostMapping
    public FaultResponse inject(
            @Valid @RequestBody FaultRequest request,
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        injector.inject(new DemoFaultInjector.Fault(request.point()));
        auditTrail.record(DemoRole.parse(role).name(), "INJECT_DEMO_FAULT", request.point().name(), "SUCCESS");
        return new FaultResponse(request.point().name(), "ARMED");
    }

    public record FaultRequest(@NotNull DemoFaultInjector.FaultPoint point) { }
    public record FaultResponse(String point, String status) { }
}
