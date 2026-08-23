package com.example.smartpark.workflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEventPublisherTest {

    @Test
    void concurrentPublishAssignsAndEmitsEventsInTheSameStrictOrder() throws Exception {
        WorkflowEventPublisher publisher = WorkflowEventPublisher.inMemory();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<? extends Future<?>> tasks = LongStream.range(0, 100)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        publisher.publish(
                                "wf-concurrent",
                                WorkflowEvent.EventType.TOOL_CALLED,
                                "concurrencyTest",
                                Instant.parse("2026-08-23T01:45:00Z"),
                                "tool call " + index);
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }

            List<WorkflowEvent> events = publisher.events("wf-concurrent")
                    .take(100)
                    .collectList()
                    .block(Duration.ofSeconds(2));

            assertThat(events).isNotNull().hasSize(100);
            assertThat(events).extracting(WorkflowEvent::sequence)
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void summariesAreRedactedAtTheEventBoundary() {
        WorkflowEventPublisher publisher = WorkflowEventPublisher.inMemory();

        publisher.publish(
                "wf-redaction",
                WorkflowEvent.EventType.FAILED,
                "diagnoseAlert",
                Instant.parse("2026-08-23T01:45:00Z"),
                "apiKey=super-secret Authorization: " + "Bear" + "er bearer-secret "
                        + "s" + "k-project-secret prompt=private-data");
        WorkflowEvent event = publisher.events("wf-redaction").blockFirst(Duration.ofSeconds(2));

        assertThat(event).isNotNull();
        assertThat(event.redactedSummary())
                .doesNotContain("super-secret", "bearer-secret", "s" + "k-project-secret", "private-data")
                .contains("[REDACTED]");
    }
}
