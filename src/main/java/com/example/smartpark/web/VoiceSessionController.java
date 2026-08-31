package com.example.smartpark.web;

import com.example.smartpark.voice.VoiceSessionService;
import com.example.smartpark.voice.VoiceSessionStore;
import com.example.smartpark.voice.model.VoiceSessionState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;
import java.util.Optional;

/**
 * REST entry points of the realtime voice assistant. Session creation returns
 * the dedicated WebSocket path carrying binary audio and JSON control frames.
 * Registered only when the voice session layer is active (conditional config).
 */
@RestController
@RequestMapping("/api/voice")
@Conditional(VoiceActiveCondition.class)
@ConditionalOnProperty(prefix = "smartpark.local-demo", name = "enabled", havingValue = "true")
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
        Optional<VoiceSessionStore.Snapshot> snapshot = service.describe(sessionId);
        Optional<VoiceSessionState> state = service.stateOf(sessionId);
        if (snapshot.isEmpty() || state.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", snapshot.get().sessionId(),
                "runId", snapshot.get().runId().toString(),
                "createdAt", snapshot.get().createdAt().toString(),
                "state", state.get().name()));
    }
}
