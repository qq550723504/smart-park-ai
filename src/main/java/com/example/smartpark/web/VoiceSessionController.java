package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST entry points of the realtime voice assistant. Session creation returns
 * the dedicated WebSocket path carrying binary audio and JSON control frames.
 * Registered only when the voice session layer is active (conditional config).
 */
@RestController
@RequestMapping("/api/voice")
@Conditional(VoiceActiveCondition.class)
public class VoiceSessionController {

    private final VoiceSessionService service;

    public VoiceSessionController(VoiceSessionService service) {
        this.service = service;
    }

    @PostMapping("/sessions")
    @ResponseBody
    public Map<String, String> create() {
        var created = service.createSession(null);
        return Map.of(
                "sessionId", created.sessionId(),
                "runId", created.runId(),
                "wsPath", "/ws/voice/sessions/" + created.sessionId());
    }

    @GetMapping("/sessions/{sessionId}")
    @ResponseBody
    public ResponseEntity describe(@PathVariable String sessionId) {
        return service.describe(sessionId)
                .<ResponseEntity>map(snapshot -> ResponseEntity.ok(Map.of(
                        "sessionId", snapshot.sessionId(),
                        "runId", snapshot.runId().toString(),
                        "state", snapshot.createdAt().toString())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
