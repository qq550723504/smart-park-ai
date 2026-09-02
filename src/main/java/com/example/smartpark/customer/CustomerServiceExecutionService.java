package com.example.smartpark.customer;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.workflow.CustomerServiceWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Adds safe, replayable execution events around the existing customer workflow. */
public final class CustomerServiceExecutionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomerServiceExecutionService.class);
    private static final String ACTOR = "customer-service";

    private final CustomerServiceWorkflow workflow;
    private final ExecutionEventPublisher publisher;

    public CustomerServiceExecutionService(CustomerServiceWorkflow workflow, ExecutionEventPublisher publisher) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.publisher = publisher;
    }

    public CustomerServiceExecutionResult handle(String question, String idempotencyKey) {
        return execute(() -> workflow.handle(question, idempotencyKey));
    }

    public CustomerServiceExecutionResult reply(String sessionId, String question, String idempotencyKey) {
        return execute(() -> workflow.reply(sessionId, question, idempotencyKey));
    }

    private CustomerServiceExecutionResult execute(Supplier<CustomerServiceResult> operation) {
        UUID runId = UUID.randomUUID();
        emit(runId, ExecutionStage.INPUT_CAPTURE, ExecutionEventType.RUN_STARTED,
                ExecutionStatus.RUNNING, "客服请求已接收");
        try {
            emit(runId, ExecutionStage.UNDERSTANDING, ExecutionEventType.NODE_STARTED,
                    ExecutionStatus.RUNNING, "开始识别服务意图");
            CustomerServiceResult result = operation.get();
            emit(runId, ExecutionStage.UNDERSTANDING, ExecutionEventType.NODE_COMPLETED,
                    ExecutionStatus.SUCCEEDED, "服务意图识别完成");
            emit(runId, ExecutionStage.TOOL_EXECUTION, ExecutionEventType.NODE_STARTED,
                    ExecutionStatus.RUNNING, "开始检索园区知识");
            emit(runId, ExecutionStage.TOOL_EXECUTION, ExecutionEventType.NODE_COMPLETED,
                    ExecutionStatus.SUCCEEDED,
                    "知识检索完成，命中 " + result.knowledgeCitations().size() + " 条依据");
            emit(runId, ExecutionStage.RESPONSE_DELIVERY, ExecutionEventType.NODE_STARTED,
                    ExecutionStatus.RUNNING, "开始生成安全答复");
            emit(runId, ExecutionStage.RESPONSE_DELIVERY, ExecutionEventType.NODE_COMPLETED,
                    ExecutionStatus.SUCCEEDED, result.needsHuman() ? "已转人工客服" : "已生成客服答复");
            emit(runId, ExecutionStage.COMPLETION, ExecutionEventType.COMPLETED,
                    ExecutionStatus.SUCCEEDED, "客服请求处理完成");
            return new CustomerServiceExecutionResult(runId, result);
        } catch (RuntimeException | Error failure) {
            emit(runId, ExecutionStage.FAILURE, ExecutionEventType.FAILED,
                    ExecutionStatus.FAILED, "客服请求执行失败");
            throw failure;
        }
    }

    private void emit(UUID runId, ExecutionStage stage, ExecutionEventType type,
                      ExecutionStatus status, String safeSummary) {
        if (publisher == null) return;
        try {
            publisher.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(),
                    ExecutionScenario.CUSTOMER_SERVICE, ACTOR, stage, type, status, safeSummary, null));
        } catch (RuntimeException failure) {
            LOGGER.warn("Customer service execution event publication failed at {}", type);
        }
    }

    public record CustomerServiceExecutionResult(UUID runId, CustomerServiceResult result) {
        public CustomerServiceExecutionResult {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(result, "result");
        }
    }
}
