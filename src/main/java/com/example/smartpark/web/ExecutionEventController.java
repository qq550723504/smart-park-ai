package com.example.smartpark.web;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.ExecutionEventPublisher.Subscription;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Read-only unified execution trace API. The SSE endpoint replays history and
 * stays open until a terminal event closes the stream; unknown runs are 404.
 */
@RestController
class ExecutionEventController {

    private final ExecutionEventPublisher publisher;
    private final ObjectProvider<ExecutionEventArchive> archives;

    ExecutionEventController(ExecutionEventPublisher publisher,
                             ObjectProvider<ExecutionEventArchive> archives) {
        this.publisher = publisher;
        this.archives = archives;
    }

    @GetMapping("/api/executions/{runId}")
    ExecutionDtos.ExecutionRunDto summary(
            @PathVariable UUID runId,
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        authorize(runId, role);
        requireKnownRun(runId);
        return ExecutionDtos.ExecutionRunDto.of(publisher.status(runId), publisher.history(runId).size());
    }

    @GetMapping(value = "/api/executions/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<ExecutionDtos.ExecutionEventDto>> events(
            @PathVariable UUID runId,
            @RequestHeader(value = "X-Demo-Role", required = false) String role,
            @RequestParam(value = "role", required = false) String queryRole) {
        authorize(runId, role == null ? queryRole : role);
        hydrate(runId);
        Sinks.Many<ExecutionDtos.ExecutionEventDto> sink = Sinks.many().unicast().onBackpressureBuffer();
        try {
            Subscription subscription = publisher.subscribe(runId, event -> {
                sink.tryEmitNext(ExecutionDtos.ExecutionEventDto.from(event));
                if (event.isTerminal()) {
                    sink.tryEmitComplete();
                }
            });
            // A disconnected SSE client cancels the flux; wire that cancellation
            // back to Subscription.close() so the consumer is removed from the
            // run state instead of leaking until the run terminates.
            return sink.asFlux()
                    .doOnCancel(subscription::close)
                    .map(ExecutionEventController::toSse);
        } catch (IllegalArgumentException exception) {
            throw new NoSuchElementException("Unknown execution run: " + runId);
        }
    }

    private void requireKnownRun(UUID runId) {
        hydrate(runId);
        if (publisher.history(runId).isEmpty() && "UNKNOWN".equals(publisher.status(runId))) {
            throw new NoSuchElementException("Unknown execution run: " + runId);
        }
    }

    private void authorize(UUID runId, String role) {
        archives.orderedStream().forEach(archive -> archive.authorize(runId, role));
    }

    private void hydrate(UUID runId) {
        synchronized (publisher) {
            if (!publisher.history(runId).isEmpty() || !"UNKNOWN".equals(publisher.status(runId))) return;
            archives.orderedStream().map(archive -> archive.history(runId)).filter(history -> !history.isEmpty())
                    .findFirst().ifPresent(history -> history.forEach(event -> {
                        try {
                            publisher.publish(event);
                        } catch (IllegalArgumentException | IllegalStateException alreadyHydrated) {
                            // Another request restored the same durable trace first.
                        }
                    }));
        }
    }

    private static ServerSentEvent<ExecutionDtos.ExecutionEventDto> toSse(ExecutionDtos.ExecutionEventDto dto) {
        return ServerSentEvent.<ExecutionDtos.ExecutionEventDto>builder(dto)
                .id(Long.toString(dto.sequence()))
                .event(dto.eventType())
                .build();
    }
}
