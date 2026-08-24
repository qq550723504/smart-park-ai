package com.example.smartpark.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerServiceConcurrencyBoundaryTest {
    @Test
    void workflowUsesCompletionReservationsForIdempotency() throws Exception {
        Path workflow = Path.of("src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java");
        String source = Files.readString(workflow, StandardCharsets.UTF_8);

        assertThat(source).contains("CompletableFuture");
        assertThat(source).contains("idempotencyReservations");
        assertThat(source).doesNotContain("idempotencyLocks");
        assertThat(source).doesNotContain("public synchronized CustomerServiceResult handle");
        assertThat(source).doesNotContain("public synchronized CustomerServiceResult reply");
    }
}
