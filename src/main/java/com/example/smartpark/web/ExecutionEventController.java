package com.example.smartpark.web;

import com.example.smartpark.execution.ExecutionEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
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

    ExecutionEventController(ExecutionEventPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/api/executions/{runId}")
    ExecutionDtos.ExecutionRunDto summary(@PathVariable UUID runId) {
        requireKnownRun(runId);
        return ExecutionDtos.ExecutionRunDto.of(publisher.status(runId), publisher.history(runId).size());
    }

    @GetMapping(value = "/api/executions/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<ExecutionDtos.ExecutionEventDto>> events(@PathVariable UUID runId) {
        Sinks.Many<ExecutionDtos.ExecutionEventDto> sink = Sinks.many().unicast().onBackpressureBuffer();
        try {
            publisher.subscribe(runId, event -> {
                sink.tryEmitNext(ExecutionDtos.ExecutionEventDto.from(event));
                if (event.isTerminal()) {
                    sink.tryEmitComplete();
                }
            });
        } catch (IllegalArgumentException exception) {
            throw new NoSuchElementException("Unknown execution run: " + runId);
        }
        return sink.asFlux().map(ExecutionEventController::toSse);
    }

    private void requireKnownRun(UUID runId) {
        if (publisher.history(runId).isEmpty() && "UNKNOWN".equals(publisher.status(runId))) {
            throw new NoSuchElementException("Unknown execution run: " + runId);
        }
    }

    private static ServerSentEvent<ExecutionDtos.ExecutionEventDto> toSse(ExecutionDtos.ExecutionEventDto dto) {
        return ServerSentEvent.<ExecutionDtos.ExecutionEventDto>builder(dto)
                .id(Long.toString(dto.sequence()))
                .event(dto.eventType())
                .build();
    }
}
