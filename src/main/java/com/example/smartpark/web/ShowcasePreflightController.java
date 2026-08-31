package com.example.smartpark.web;

import com.example.smartpark.showcase.ShowcasePreflightReport;
import com.example.smartpark.showcase.ShowcasePreflightService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Objects;

@RestController
@RequestMapping("/api/showcase")
@ConditionalOnProperty(prefix = "smartpark.local-demo", name = "enabled", havingValue = "true")
public final class ShowcasePreflightController {

    private final ShowcasePreflightService preflight;

    public ShowcasePreflightController(ShowcasePreflightService preflight) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    @PostMapping("/preflight")
    public ShowcasePreflightReport preflight(
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        return preflight.run();
    }
}
