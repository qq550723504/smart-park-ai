package com.example.smartpark.showcase;

import com.example.smartpark.port.workorder.WorkOrderPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RejectingPreflightWorkOrderPortTest {

    @Test
    void workOrderBoundaryAllowsEmptyReadAndRejectsEveryWrite() {
        WorkOrderPort port = new RejectingPreflightWorkOrderPort();

        assertThat(port.findByWorkflowId("showcase-preflight")).isEmpty();
        assertThatThrownBy(() -> port.create("wf", "ALT-POWER-001", "summary"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("preflight work-order writes are forbidden");
    }
}
