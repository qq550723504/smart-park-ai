package com.example.smartpark.customer;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;

class CustomerServiceExecutionServiceTest {

    @Test
    void publishesOrderedSafeEventsForNewCustomerSession() {
        var publisher = spy(new InMemoryExecutionEventPublisher());
        var service = new CustomerServiceExecutionService(
                new CustomerServiceWorkflow(new MockParkFixture().knowledge()), publisher);

        var executed = service.handle("访客停车怎么收费？", "request-1");
        List<ExecutionEvent> events = publisher.history(executed.runId());

        assertThat(executed.runId()).isNotNull();
        assertThat(events).extracting(ExecutionEvent::eventType).containsExactly(
                ExecutionEventType.RUN_STARTED, ExecutionEventType.NODE_STARTED,
                ExecutionEventType.NODE_COMPLETED, ExecutionEventType.NODE_STARTED,
                ExecutionEventType.NODE_COMPLETED, ExecutionEventType.NODE_STARTED,
                ExecutionEventType.NODE_COMPLETED, ExecutionEventType.COMPLETED);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.scenario()).isEqualTo(ExecutionScenario.CUSTOMER_SERVICE);
            assertThat(event.safeSummary()).doesNotContain("停车怎么收费");
        });
        assertThat(events.get(4).safeSummary()).matches("知识检索完成，命中 \\d+ 条依据");
    }

    @Test
    void createsIndependentTraceForAReplyAndMarksHumanHandoffSafely() {
        var publisher = spy(new InMemoryExecutionEventPublisher());
        var workflow = new CustomerServiceWorkflow(new MockParkFixture().knowledge());
        var service = new CustomerServiceExecutionService(workflow, publisher);
        var first = service.handle("访客停车怎么收费？", "first");
        var second = service.reply(first.result().sessionId(), "A1 洗手间漏水，需要报修", "second");

        assertThat(second.runId()).isNotEqualTo(first.runId());
        assertThat(publisher.history(second.runId()).get(6).safeSummary()).isEqualTo("已转人工客服");
        assertThat(publisher.status(second.runId())).isEqualTo("COMPLETED");
    }

    @Test
    void publishesStableFailureAndRethrowsUnhandledWorkflowFailure() {
        var publisher = spy(new InMemoryExecutionEventPublisher());
        var workflow = org.mockito.Mockito.mock(CustomerServiceWorkflow.class);
        org.mockito.Mockito.when(workflow.handle("q", "key"))
                .thenThrow(new IllegalStateException("provider secret response"));
        var service = new CustomerServiceExecutionService(workflow, publisher);

        assertThatThrownBy(() -> service.handle("q", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider secret response");
        ArgumentCaptor<ExecutionEvent> captured = ArgumentCaptor.forClass(ExecutionEvent.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publish(captured.capture());
        var events = publisher.history(captured.getAllValues().get(0).runId());
        assertThat(events).extracting(ExecutionEvent::eventType)
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.NODE_STARTED,
                        ExecutionEventType.FAILED);
        assertThat(events.get(2).safeSummary())
                .isEqualTo("客服请求执行失败")
                .doesNotContain("secret");
    }
}
