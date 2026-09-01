package com.example.smartpark.operations;

import com.example.smartpark.audit.AuditTrail;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OperationsMetricsTest {

    @Test
    void emptyRuntimeProducesZeroedSafeMetrics() {
        OperationsMetrics metrics = new OperationsMetrics(
                WorkflowExecutionStore.inMemory(),
                mock(CustomerServiceWorkflow.class),
                new AuditTrail());

        OperationsMetrics.Snapshot snapshot = metrics.snapshot();

        assertThat(snapshot.workflowCount()).isZero();
        assertThat(snapshot.customerSessionCount()).isZero();
        assertThat(snapshot.auditEntryCount()).isZero();
    }
}
