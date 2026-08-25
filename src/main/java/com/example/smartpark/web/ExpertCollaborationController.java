package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class ExpertCollaborationController {
    private final ExpertCollaborationService service;
    public ExpertCollaborationController(ExpertCollaborationService service) { this.service = service; }

    @PostMapping("/api/expert-collaboration/runs")
    public ResponseEntity<Map<String, String>> start(@RequestBody Map<String, String> body) {
        CollaborationRun run = service.start(body == null ? null : body.get("question"));
        return ResponseEntity.accepted().body(Map.of("runId", run.runId().toString(),
                "statusUrl", "/api/expert-collaboration/runs/" + run.runId(),
                "eventsUrl", "/api/executions/" + run.runId() + "/events"));
    }

    @GetMapping("/api/expert-collaboration/runs/{runId}")
    public CollaborationRun status(@PathVariable UUID runId) { return service.get(runId); }
}
