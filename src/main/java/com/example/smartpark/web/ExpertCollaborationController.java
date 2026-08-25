package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class ExpertCollaborationController {
    private final ObjectProvider<ExpertCollaborationService> serviceProvider;

    public ExpertCollaborationController(ObjectProvider<ExpertCollaborationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    private ExpertCollaborationService service() {
        ExpertCollaborationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "expert collaboration is unavailable");
        }
        return service;
    }

    @PostMapping("/api/expert-collaboration/runs")
    public ResponseEntity<Map<String, String>> start(@RequestBody Map<String, String> body) {
        CollaborationRun run = service().start(body == null ? null : body.get("question"));
        return ResponseEntity.accepted().body(Map.of("runId", run.runId().toString(),
                "statusUrl", "/api/expert-collaboration/runs/" + run.runId(),
                "eventsUrl", "/api/executions/" + run.runId() + "/events"));
    }

    @GetMapping("/api/expert-collaboration/runs/{runId}")
    public CollaborationRun status(@PathVariable UUID runId) { return service().get(runId); }
}
