package com.example.smartpark.web;

import com.example.smartpark.governance.GovernanceOverview;
import com.example.smartpark.governance.GovernanceOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/governance")
public final class GovernanceOverviewController {

    private final GovernanceOverviewService service;

    public GovernanceOverviewController(GovernanceOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public GovernanceOverview overview() {
        return service.snapshot();
    }
}
