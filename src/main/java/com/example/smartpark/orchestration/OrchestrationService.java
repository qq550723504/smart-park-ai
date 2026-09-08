package com.example.smartpark.orchestration;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import com.example.smartpark.orchestration.OrchestrationPorts.Capabilities;
import com.example.smartpark.orchestration.OrchestrationPorts.ChildOutcome;
import com.example.smartpark.orchestration.OrchestrationPorts.EvidenceOutcome;
import com.example.smartpark.orchestration.OrchestrationPorts.StartedChild;
import com.example.smartpark.orchestration.OrchestrationPorts.WorkflowOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Executes one typed cross-scenario definition by calling existing application
 * services. It owns orchestration state only; child business state remains in
 * the child services and is referenced by run id.
 */
public final class OrchestrationService {
    private static final Set<String> START_ROLES = Set.of("VIEWER", "OPERATOR", "APPROVER", "ADMIN");
    private static final Set<String> SECURITY_ROLES = Set.of("APPROVER", "ADMIN");
    private static final Set<String> WORKFLOW_ROLES = Set.of("OPERATOR", "APPROVER", "ADMIN");
    private static final String SAFE_STEP_FAILURE = "步骤执行失败，未采用未确认结果";

    private final OrchestrationRunStore store;
    private final OrchestrationPorts.CapabilityReader capabilities;
    private final OrchestrationPorts.OperationsRunner operations;
    private final OrchestrationPorts.EnergyReader energy;
    private final OrchestrationPorts.CollaborationRunner collaboration;
    private final OrchestrationPorts.SecurityReader security;
    private final OrchestrationPorts.WorkflowRunner workflow;
    private final ExecutionEventPublisher events;
    private final Executor executor;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Object> runLocks = new ConcurrentHashMap<>();

    public OrchestrationService(OrchestrationRunStore store,
                                OrchestrationPorts.CapabilityReader capabilities,
                                OrchestrationPorts.OperationsRunner operations,
                                OrchestrationPorts.EnergyReader energy,
                                OrchestrationPorts.CollaborationRunner collaboration,
                                OrchestrationPorts.SecurityReader security,
                                OrchestrationPorts.WorkflowRunner workflow,
                                ExecutionEventPublisher events,
                                Executor executor,
                                Clock clock) {
        this.store = store;
        this.capabilities = capabilities;
        this.operations = operations;
        this.energy = energy;
        this.collaboration = collaboration;
        this.security = security;
        this.workflow = workflow;
        this.events = events;
        this.executor = executor;
        this.clock = clock;
    }

    public OrchestrationRunStore.StartResult start(String definitionId, OrchestrationInput input,
                                                   String idempotencyKey, String requestedBy,
                                                   String role) {
        requireText(idempotencyKey, "Idempotency-Key", 200);
        String normalizedRole = requireRole(role);
        if (!START_ROLES.contains(normalizedRole)) {
            throw new SecurityException("role is not allowed to start orchestration");
        }
        OrchestrationDefinition.steps(definitionId);
        String fingerprint = fingerprint(definitionId, input, normalizedRole);
        Instant now = clock.instant();
        OrchestrationRunStore.StartResult result = store.createOrGet(idempotencyKey.trim(), fingerprint, () -> {
            UUID id = UUID.randomUUID();
            List<OrchestrationStep> steps = OrchestrationDefinition.steps(definitionId).stream()
                    .map(OrchestrationStep::pending).toList();
            return new OrchestrationRun(id, definitionId, OrchestrationStatus.RUNNING, now, now, null,
                    normalizeActor(requestedBy), normalizedRole, input, "编排已启动", steps, List.of(), id,
                    null, null, idempotencyKey.trim(), fingerprint, false, 0, List.of());
        });
        if (result.created()) {
            publish(result.run().id(), "orchestrator", ExecutionStage.INITIALIZATION,
                    ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "园区异常联合研判已启动");
            try {
                executor.execute(() -> execute(result.run().id()));
            } catch (RuntimeException rejected) {
                failRun(result.run().id(), "编排执行资源暂不可用");
                throw rejected;
            }
        }
        return new OrchestrationRunStore.StartResult(get(result.run().id()), result.created());
    }

    public OrchestrationRun get(UUID runId) {
        OrchestrationRun run = store.find(runId)
                .orElseThrow(() -> new NoSuchElementException("Unknown orchestration run"));
        if (run.status() == OrchestrationStatus.WAITING_APPROVAL) {
            reconcileApproval(runId);
            run = store.find(runId).orElseThrow();
        }
        return run;
    }

    public OrchestrationRun cancel(UUID runId) {
        synchronized (lock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status().isTerminal()) return current;
            String childReference = current.steps().stream()
                    .filter(step -> step.status() == OrchestrationStepStatus.RUNNING
                            || step.status() == OrchestrationStepStatus.WAITING_APPROVAL)
                    .map(OrchestrationStep::runReference).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            OrchestrationStepType activeType = current.steps().stream()
                    .filter(step -> step.status() == OrchestrationStepStatus.RUNNING
                            || step.status() == OrchestrationStepStatus.WAITING_APPROVAL)
                    .map(OrchestrationStep::type).findFirst().orElse(null);
            Instant now = clock.instant();
            store.update(runId, run -> run.copy(
                    OrchestrationStatus.CANCELLED, run.startedAt(), now, "编排已取消",
                    run.steps().stream().map(step ->
                            step.status() == OrchestrationStepStatus.PENDING
                                    || step.status() == OrchestrationStepStatus.RUNNING
                                    || step.status() == OrchestrationStepStatus.WAITING_APPROVAL
                            ? step.transition(OrchestrationStepStatus.CANCELLED, now, null, null,
                                    null, null, "编排已取消") : step).toList(),
                    run.evidence(), "编排已取消", run.result(), true, run.traceEvents()));
            cancelChild(activeType, childReference);
            publish(runId, "orchestrator", ExecutionStage.COMPLETION,
                    ExecutionEventType.RUN_CANCELLED, ExecutionStatus.INTERRUPTED, "园区异常联合研判已取消");
            return getStored(runId);
        }
    }

    /** Called once after dependency wiring to recover persisted non-terminal records. */
    public void recover() {
        store.nonTerminalRuns().forEach(run -> executor.execute(() -> recover(run.id())));
    }

    private void recover(UUID runId) {
        OrchestrationRun run = getStored(runId);
        if (run.status().isTerminal()) return;
        if (run.status() == OrchestrationStatus.WAITING_APPROVAL) {
            reconcileApproval(runId);
            return;
        }
        OrchestrationStep active = run.steps().stream()
                .filter(step -> step.status() == OrchestrationStepStatus.RUNNING)
                .findFirst().orElse(null);
        if (active != null) {
            failStep(runId, active.id(), "服务恢复后无法确认子运行状态", active.required());
            if (active.required()) return;
        }
        executeLocked(runId);
    }

    private void execute(UUID runId) {
        executeLocked(runId);
    }

    private void executeLocked(UUID runId) {
        while (true) {
            OrchestrationRun run = getStored(runId);
            if (run.status().isTerminal() || run.status() == OrchestrationStatus.WAITING_APPROVAL) return;
            if (run.cancelRequested()) {
                cancel(runId);
                return;
            }
            OrchestrationStep next = run.steps().stream()
                    .filter(step -> step.status() == OrchestrationStepStatus.PENDING)
                    .findFirst().orElse(null);
            if (next == null) {
                completeRun(runId);
                return;
            }
            boolean shouldContinue = switch (next.type()) {
                case COLLECT_CONTEXT -> collectContext(runId, next);
                case OPERATIONS_ANALYSIS -> runOperations(runId, next);
                case ENERGY_TIME_SERIES -> runEnergy(runId, next);
                case EXPERT_COLLABORATION -> runCollaboration(runId, next);
                case SECURITY_REVIEW -> runSecurity(runId, next);
                case ALERT_WORKFLOW -> runWorkflow(runId, next);
                case FINAL_SUMMARY -> summarize(runId, next);
            };
            if (!shouldContinue) return;
        }
    }

    private boolean collectContext(UUID runId, OrchestrationStep step) {
        OrchestrationInput input = getStored(runId).input();
        if (!startStep(runId, step.id(), "接收异常上下文")) return false;
        List<String> refs = new ArrayList<>();
        if (input.alertId() != null) refs.add("alert:" + input.alertId());
        input.buildingIds().forEach(id -> refs.add("building:" + id));
        completeStep(runId, step.id(), "已登记问题、场景标记与资源引用", null, refs);
        return true;
    }

    private boolean runOperations(UUID runId, OrchestrationStep step) {
        Capabilities current = capabilities.current();
        if (!current.analytics() || operations == null) {
            failStep(runId, step.id(), "运营分析能力不可用", true);
            return false;
        }
        OrchestrationInput input = getStored(runId).input();
        try {
            StartedChild child = startChild(runId, step.id(), "调用现有 Operations Analysis",
                    () -> operations.start(input.question()));
            if (child == null) return false;
            ChildOutcome outcome = child.completion().join();
            if (cancelled(runId)) return false;
            if (!"COMPLETED".equals(outcome.status())) {
                String reason = "NEEDS_CLARIFICATION".equals(outcome.status())
                        ? "运营分析需要澄清，编排未猜测用户选择" : "运营分析未完成";
                failStep(runId, step.id(), reason, true);
                return false;
            }
            completeStep(runId, step.id(), safeSummary(outcome.summary(), "运营分析已完成"),
                    outcome.runId().toString(), outcome.evidenceReferences());
            return true;
        } catch (RuntimeException failure) {
            if (cancelled(runId)) return false;
            failStep(runId, step.id(), SAFE_STEP_FAILURE, true);
            return false;
        }
    }

    private boolean runEnergy(UUID runId, OrchestrationStep step) {
        OrchestrationInput input = getStored(runId).input();
        if (!input.energyRelated()) {
            skipStep(runId, step.id(), "输入与上游结果不涉及能耗", false);
            return true;
        }
        Capabilities current = capabilities.current();
        if (!current.energy() || energy == null) {
            skipStep(runId, step.id(), "能耗时序能力不可用", true);
            return true;
        }
        if (!startStep(runId, step.id(), "读取现有 Energy Time Series")) return false;
        try {
            EvidenceOutcome outcome = energy.query(input.buildingIds());
            if (cancelled(runId)) return false;
            if ("UNAVAILABLE".equals(outcome.status())) {
                failStep(runId, step.id(), "能耗时序无可用证据", false);
                return true;
            }
            completeStep(runId, step.id(), safeSummary(outcome.summary(), "能耗时序证据已读取"),
                    null, outcome.evidenceReferences());
            rememberRecommendations(runId, step.id(), outcome.recommendations());
            if ("PARTIAL".equals(outcome.status())) markStepPartial(runId, step.id(), "能耗时序存在缺失点");
            return true;
        } catch (RuntimeException failure) {
            if (cancelled(runId)) return false;
            failStep(runId, step.id(), SAFE_STEP_FAILURE, false);
            return true;
        }
    }

    private boolean runCollaboration(UUID runId, OrchestrationStep step) {
        OrchestrationInput input = getStored(runId).input();
        if (!input.crossDomain()) {
            skipStep(runId, step.id(), "当前问题不需要跨域协作", false);
            return true;
        }
        Capabilities current = capabilities.current();
        if (!current.collaboration() || collaboration == null) {
            skipStep(runId, step.id(), "专家协作能力不可用", true);
            return true;
        }
        try {
            StartedChild child = startChild(runId, step.id(), "调用现有 Expert Collaboration",
                    () -> collaboration.start(input.question()));
            if (child == null) return false;
            ChildOutcome outcome = child.completion().join();
            if (cancelled(runId)) return false;
            if (!"COMPLETED".equals(outcome.status())) {
                failStep(runId, step.id(), "专家协作未完成", false);
                return true;
            }
            completeStep(runId, step.id(), safeSummary(outcome.summary(), "专家协作已完成"),
                    outcome.runId().toString(), outcome.evidenceReferences());
            return true;
        } catch (RuntimeException failure) {
            if (cancelled(runId)) return false;
            failStep(runId, step.id(), SAFE_STEP_FAILURE, false);
            return true;
        }
    }

    private boolean runSecurity(UUID runId, OrchestrationStep step) {
        OrchestrationRun run = getStored(runId);
        if (!run.input().securityRelated()) {
            skipStep(runId, step.id(), "当前问题没有安全域信号", false);
            return true;
        }
        if (!SECURITY_ROLES.contains(run.role())) {
            skipStep(runId, step.id(), "当前角色无权访问安全事件", true);
            return true;
        }
        Capabilities current = capabilities.current();
        if (!current.security() || security == null) {
            skipStep(runId, step.id(), "安全事件能力不可用", true);
            return true;
        }
        if (!startStep(runId, step.id(), "读取现有 Security Incident 证据")) return false;
        try {
            EvidenceOutcome outcome = security.query(run.input());
            if (cancelled(runId)) return false;
            if ("UNAVAILABLE".equals(outcome.status())) {
                skipStep(runId, step.id(), "没有匹配的安全事件证据", true);
                return true;
            }
            completeStep(runId, step.id(), safeSummary(outcome.summary(), "安全事件证据已读取"),
                    null, outcome.evidenceReferences());
            rememberRecommendations(runId, step.id(), outcome.recommendations());
            return true;
        } catch (RuntimeException failure) {
            if (cancelled(runId)) return false;
            failStep(runId, step.id(), SAFE_STEP_FAILURE, false);
            return true;
        }
    }

    private boolean runWorkflow(UUID runId, OrchestrationStep step) {
        OrchestrationRun run = getStored(runId);
        if (!run.input().requestAction()) {
            skipStep(runId, step.id(), "本次研判未请求处置动作", false);
            return true;
        }
        if (!WORKFLOW_ROLES.contains(run.role())) {
            skipStep(runId, step.id(), "当前角色无权启动处置工作流", true);
            return true;
        }
        Capabilities current = capabilities.current();
        if (!current.workflow() || workflow == null) {
            skipStep(runId, step.id(), "告警工作流能力不可用", true);
            return true;
        }
        try {
            WorkflowOutcome outcome = startWorkflow(runId, step.id(), run.input().alertId());
            if (outcome == null) return false;
            if (cancelled(runId)) return false;
            if ("WAITING_APPROVAL".equals(outcome.status())) {
                waitForApproval(runId, step.id(), outcome);
                return false;
            }
            return applyWorkflowOutcome(runId, step.id(), outcome);
        } catch (RuntimeException failure) {
            if (cancelled(runId)) return false;
            failStep(runId, step.id(), SAFE_STEP_FAILURE, false);
            return true;
        }
    }

    private boolean summarize(UUID runId, OrchestrationStep step) {
        if (!startStep(runId, step.id(), "汇总已验证证据与子运行状态")) return false;
        OrchestrationRun run = getStored(runId);
        List<String> summaries = run.steps().stream()
                .filter(item -> item.status() == OrchestrationStepStatus.COMPLETED)
                .map(OrchestrationStep::outputSummary).filter(java.util.Objects::nonNull).toList();
        String conclusion = summaries.isEmpty() ? "没有形成可验证结论" : String.join("；", summaries);
        completeStep(runId, step.id(), conclusion, null, run.evidence());
        return true;
    }

    private void reconcileApproval(UUID runId) {
        synchronized (lock(runId)) {
            reconcileApprovalLocked(runId);
        }
    }

    private void reconcileApprovalLocked(UUID runId) {
        OrchestrationRun run = getStored(runId);
        if (run.status() != OrchestrationStatus.WAITING_APPROVAL) return;
        OrchestrationStep step = run.steps().stream()
                .filter(item -> item.status() == OrchestrationStepStatus.WAITING_APPROVAL)
                .findFirst().orElse(null);
        if (step == null || step.runReference() == null || workflow == null) {
            failStep(runId, step == null ? "alert-workflow" : step.id(),
                    "审批子运行无法恢复", false);
            resumeRunAfterApproval(runId, "审批子运行无法恢复");
            executor.execute(() -> execute(runId));
            return;
        }
        try {
            WorkflowOutcome outcome = workflow.get(step.runReference());
            if ("WAITING_APPROVAL".equals(outcome.status())) return;
            publish(runId, "orchestrator", ExecutionStage.HUMAN_APPROVAL,
                    ExecutionEventType.APPROVAL_RESUMED, ExecutionStatus.RUNNING, "人工审批结果已由现有工作流记录");
            applyWorkflowOutcome(runId, step.id(), outcome);
            resumeRunAfterApproval(runId, outcome.approvalResult());
            executor.execute(() -> execute(runId));
        } catch (RuntimeException missingChild) {
            failStep(runId, step.id(), "服务恢复后无法确认审批子运行", false);
            resumeRunAfterApproval(runId, "审批子运行不可恢复");
            executor.execute(() -> execute(runId));
        }
    }

    private boolean applyWorkflowOutcome(UUID runId, String stepId, WorkflowOutcome outcome) {
        if ("COMPLETED".equals(outcome.status()) || "REJECTED".equals(outcome.status())) {
            String summary = "REJECTED".equals(outcome.status()) ? "人工拒绝了处置动作" : "处置工作流已完成";
            completeStep(runId, stepId, summary, outcome.workflowId(), outcome.evidenceReferences());
            return true;
        }
        failStep(runId, stepId, "处置工作流未完成", false);
        return true;
    }

    private void waitForApproval(UUID runId, String stepId, WorkflowOutcome outcome) {
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            Instant now = clock.instant();
            store.update(runId, run -> {
                List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                int index = stepIndex(steps, stepId);
                steps.set(index, steps.get(index).transition(OrchestrationStepStatus.WAITING_APPROVAL,
                        now, null, "等待现有 Human Approval", outcome.workflowId(),
                        outcome.evidenceReferences(), null));
                return run.copy(OrchestrationStatus.WAITING_APPROVAL,
                        run.startedAt(), null, "等待人工审批", steps, run.evidence(), null,
                        run.result(), run.cancelRequested(), run.traceEvents());
            });
            publish(runId, "orchestrator", ExecutionStage.HUMAN_APPROVAL,
                    ExecutionEventType.WAITING_APPROVAL, ExecutionStatus.RUNNING, "等待人工审批");
        }
    }

    private void resumeRunAfterApproval(UUID runId, String approvalResult) {
        store.update(runId, run -> run.copy(OrchestrationStatus.RUNNING,
                run.startedAt(), null, approvalResult == null ? "审批后恢复编排" : approvalResult,
                run.steps(), run.evidence(), null, run.result(), run.cancelRequested(), run.traceEvents()));
    }

    private boolean startStep(UUID runId, String stepId, String inputSummary) {
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return false;
            Instant now = clock.instant();
            updateStep(runId, stepId, step -> step.transition(OrchestrationStepStatus.RUNNING,
                    now, inputSummary, null, null, null, null));
            publish(runId, stepId, stageFor(stepId), ExecutionEventType.STEP_STARTED,
                    ExecutionStatus.RUNNING, inputSummary);
            return true;
        }
    }

    private StartedChild startChild(UUID runId, String stepId, String inputSummary,
                                    Supplier<StartedChild> start) {
        synchronized (lock(runId)) {
            if (!startStep(runId, stepId, inputSummary)) return null;
            StartedChild child = start.get();
            rememberChildReference(runId, stepId, child.runId().toString());
            return child;
        }
    }

    private WorkflowOutcome startWorkflow(UUID runId, String stepId, String alertId) {
        synchronized (lock(runId)) {
            if (!startStep(runId, stepId, "调用现有 Alert Workflow")) return null;
            WorkflowOutcome outcome = workflow.start(alertId);
            rememberChildReference(runId, stepId, outcome.workflowId());
            return outcome;
        }
    }

    private void completeStep(UUID runId, String stepId, String outputSummary,
                              String childRun, List<String> evidence) {
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            Instant now = clock.instant();
            List<String> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
            updateStep(runId, stepId, step -> step.transition(OrchestrationStepStatus.COMPLETED,
                    now, null, outputSummary, childRun, safeEvidence, null));
            appendEvidence(runId, safeEvidence);
            publish(runId, stepId, stageFor(stepId), ExecutionEventType.STEP_COMPLETED,
                    ExecutionStatus.RUNNING, outputSummary);
        }
    }

    private void skipStep(UUID runId, String stepId, String reason, boolean partial) {
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            Instant now = clock.instant();
            updateStep(runId, stepId, step -> step.transition(OrchestrationStepStatus.SKIPPED,
                    now, null, reason, null, List.of(), partial ? reason : null));
            publish(runId, stepId, stageFor(stepId), ExecutionEventType.STEP_SKIPPED,
                    ExecutionStatus.RUNNING, reason);
        }
    }

    private void failStep(UUID runId, String stepId, String reason, boolean required) {
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            Instant now = clock.instant();
            updateStep(runId, stepId, step -> step.transition(required
                            ? OrchestrationStepStatus.BLOCKED : OrchestrationStepStatus.FAILED,
                    now, null, null, null, null, reason));
            publish(runId, stepId, ExecutionStage.FAILURE, ExecutionEventType.STEP_FAILED,
                    ExecutionStatus.FAILED, reason);
            if (required) failRun(runId, reason);
        }
    }

    private void markStepPartial(UUID runId, String stepId, String reason) {
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            updateStep(runId, stepId, step -> new OrchestrationStep(step.id(), step.type(), step.capability(),
                    step.required(), step.status(), step.startedAt(), step.completedAt(), step.inputSummary(),
                    step.outputSummary(), step.runReference(), step.evidenceReferences(),
                    step.recommendations(), reason));
        }
    }

    private void rememberRecommendations(UUID runId, String stepId, List<String> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return;
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            updateStep(runId, stepId, step -> step.withRecommendations(recommendations));
        }
    }

    private boolean rememberChildReference(UUID runId, String stepId, String childRun) {
        if (childRun == null) return true;
        synchronized (lock(runId)) {
            if (getStored(runId).status().isTerminal()) return false;
            updateStep(runId, stepId, step -> step.transition(step.status(), clock.instant(),
                    null, null, childRun, null, step.failureReason()));
            return true;
        }
    }

    private void appendEvidence(UUID runId, List<String> evidence) {
        if (evidence.isEmpty()) return;
        store.update(runId, run -> {
            LinkedHashSet<String> merged = new LinkedHashSet<>(run.evidence());
            merged.addAll(evidence);
            return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), run.steps(),
                    List.copyOf(merged), run.failureReason(), run.result(), run.cancelRequested(), run.traceEvents());
        });
    }

    private void updateStep(UUID runId, String stepId,
                            java.util.function.UnaryOperator<OrchestrationStep> transition) {
        store.update(runId, run -> {
            List<OrchestrationStep> steps = new ArrayList<>(run.steps());
            int index = stepIndex(steps, stepId);
            steps.set(index, transition.apply(steps.get(index)));
            return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), steps,
                    run.evidence(), run.failureReason(), run.result(), run.cancelRequested(), run.traceEvents());
        });
    }

    private static int stepIndex(List<OrchestrationStep> steps, String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) return i;
        }
        throw new NoSuchElementException("Unknown orchestration step");
    }

    private void completeRun(UUID runId) {
        synchronized (lock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status().isTerminal()) return;
            List<String> partialReasons = current.steps().stream()
                    .map(OrchestrationStep::failureReason).filter(java.util.Objects::nonNull).distinct().toList();
            OrchestrationStatus status = partialReasons.isEmpty()
                    ? OrchestrationStatus.COMPLETED : OrchestrationStatus.PARTIAL;
            List<String> skipped = current.steps().stream()
                    .filter(step -> step.status() == OrchestrationStepStatus.SKIPPED)
                    .map(OrchestrationStep::id).toList();
            Map<String, String> childRuns = new LinkedHashMap<>();
            current.steps().stream().filter(step -> step.runReference() != null)
                    .forEach(step -> childRuns.put(step.id(), step.runReference()));
            String conclusion = current.steps().stream()
                    .filter(step -> step.type() == OrchestrationStepType.FINAL_SUMMARY)
                    .map(OrchestrationStep::outputSummary).findFirst().orElse("没有形成可验证结论");
            String approval = current.steps().stream()
                    .filter(step -> step.type() == OrchestrationStepType.ALERT_WORKFLOW)
                    .map(OrchestrationStep::outputSummary).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            List<String> recommendations = current.steps().stream()
                    .flatMap(step -> step.recommendations().stream()).distinct().toList();
            if (recommendations.isEmpty()) recommendations = List.of("请根据已列出的证据与未完成项进行人工复核");
            OrchestrationResult result = new OrchestrationResult(conclusion,
                    recommendations, current.evidence(),
                    sourceReferences(current), childRuns, skipped, partialReasons, approval);
            Instant now = clock.instant();
            store.update(runId, run -> run.copy(status, run.startedAt(), now, conclusion,
                    run.steps(), run.evidence(), null, result, run.cancelRequested(), run.traceEvents()));
            publish(runId, "orchestrator", ExecutionStage.COMPLETION, ExecutionEventType.RUN_COMPLETED,
                    ExecutionStatus.SUCCEEDED, status == OrchestrationStatus.PARTIAL
                            ? "园区异常联合研判部分完成" : "园区异常联合研判完成");
        }
    }

    private List<String> sourceReferences(OrchestrationRun run) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        run.steps().forEach(step -> {
            if (step.status() == OrchestrationStepStatus.COMPLETED) sources.add(step.capability());
        });
        return List.copyOf(sources);
    }

    private void failRun(UUID runId, String reason) {
        synchronized (lock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status().isTerminal()) return;
            Instant now = clock.instant();
            store.update(runId, run -> run.copy(OrchestrationStatus.FAILED,
                    run.startedAt(), now, "编排失败", run.steps(), run.evidence(),
                    reason, run.result(), run.cancelRequested(), run.traceEvents()));
            publish(runId, "orchestrator", ExecutionStage.FAILURE,
                    ExecutionEventType.RUN_FAILED, ExecutionStatus.FAILED, "园区异常联合研判失败");
        }
    }

    private void publish(UUID runId, String actor, ExecutionStage stage,
                         ExecutionEventType type, ExecutionStatus status, String summary) {
        synchronized (lock(runId)) {
            OrchestrationRun updated = store.update(runId, run -> {
                long sequence = run.traceEvents().size() + 1L;
                List<OrchestrationTraceRecord> trace = new ArrayList<>(run.traceEvents());
                trace.add(new OrchestrationTraceRecord(UUID.randomUUID(), sequence, clock.instant(), actor,
                        stage, type, status, summary));
                return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), run.steps(),
                        run.evidence(), run.failureReason(), run.result(), run.cancelRequested(), trace);
            });
            OrchestrationTraceRecord record = updated.traceEvents().get(updated.traceEvents().size() - 1);
            try {
                events.publish(new ExecutionEvent(record.eventId(), updated.traceId(), record.sequence(),
                        record.timestamp(), ExecutionScenario.ORCHESTRATION, record.actor(), record.stage(),
                        record.eventType(), record.status(), record.safeSummary(), null));
            } catch (IllegalArgumentException | IllegalStateException duplicateOrClosed) {
                // Durable trace remains authoritative and can rehydrate the in-memory projection.
            }
        }
    }

    private void cancelChild(OrchestrationStepType type, String reference) {
        if (type == null || reference == null) return;
        try {
            if (type == OrchestrationStepType.OPERATIONS_ANALYSIS && operations != null) {
                operations.cancel(UUID.fromString(reference));
            } else if (type == OrchestrationStepType.EXPERT_COLLABORATION && collaboration != null) {
                collaboration.cancel(UUID.fromString(reference));
            }
        } catch (RuntimeException ignored) {
            // Orchestration is already terminal; an uninterruptible child cannot launch later steps.
        }
    }

    private boolean cancelled(UUID runId) {
        return getStored(runId).status() == OrchestrationStatus.CANCELLED;
    }

    private OrchestrationRun getStored(UUID runId) {
        return store.find(runId).orElseThrow(() -> new NoSuchElementException("Unknown orchestration run"));
    }

    private Object lock(UUID runId) {
        return runLocks.computeIfAbsent(runId, ignored -> new Object());
    }

    private static ExecutionStage stageFor(String stepId) {
        return switch (stepId) {
            case "collect-context" -> ExecutionStage.INPUT_CAPTURE;
            case "operations-analysis", "energy-time-series", "security-review" -> ExecutionStage.ANALYSIS;
            case "expert-collaboration" -> ExecutionStage.PLANNING;
            case "alert-workflow" -> ExecutionStage.TOOL_EXECUTION;
            case "final-summary" -> ExecutionStage.COMPLETION;
            default -> ExecutionStage.ANALYSIS;
        };
    }

    private static String safeSummary(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeActor(String value) {
        return value == null || value.isBlank() ? "demo-user" : requireText(value, "requestedBy", 100);
    }

    private static String requireRole(String value) {
        return requireText(value, "role", 30).toUpperCase();
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    private static String fingerprint(String definitionId, OrchestrationInput input, String role) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((definitionId + "\n" + role + "\n" + input)
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
