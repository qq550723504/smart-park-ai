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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Executes one typed cross-scenario definition by calling existing application
 * services. It owns orchestration state only; child business state remains in
 * the child services and is referenced by run id.
 */
public final class OrchestrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationService.class);
    private static final Set<String> START_ROLES = Set.of("VIEWER", "OPERATOR", "APPROVER", "ADMIN");
    private static final Set<String> SECURITY_ROLES = Set.of("APPROVER", "ADMIN");
    private static final Set<String> WORKFLOW_ROLES = Set.of("OPERATOR", "APPROVER", "ADMIN");
    private static final String SAFE_STEP_FAILURE = "步骤执行失败，未采用未确认结果";
    private static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration MAX_APPROVAL_TIMEOUT = Duration.ofDays(1);

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
    private final Duration approvalTimeout;
    private final ConcurrentHashMap<UUID, RunLock> runLocks = new ConcurrentHashMap<>();

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
        this(store, capabilities, operations, energy, collaboration, security, workflow,
                events, executor, clock, DEFAULT_APPROVAL_TIMEOUT);
    }

    public OrchestrationService(OrchestrationRunStore store,
                                OrchestrationPorts.CapabilityReader capabilities,
                                OrchestrationPorts.OperationsRunner operations,
                                OrchestrationPorts.EnergyReader energy,
                                OrchestrationPorts.CollaborationRunner collaboration,
                                OrchestrationPorts.SecurityReader security,
                                OrchestrationPorts.WorkflowRunner workflow,
                                ExecutionEventPublisher events,
                                Executor executor,
                                Clock clock,
                                Duration approvalTimeout) {
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
        if (approvalTimeout == null || approvalTimeout.isZero() || approvalTimeout.isNegative()
                || approvalTimeout.compareTo(MAX_APPROVAL_TIMEOUT) > 0) {
            throw new IllegalArgumentException("approvalTimeout must be positive and no greater than one day");
        }
        this.approvalTimeout = approvalTimeout;
    }

    public OrchestrationRunStore.StartResult start(String definitionId, OrchestrationInput input,
                                                   String idempotencyKey, String requestedBy,
                                                   String role) {
        requireText(idempotencyKey, "Idempotency-Key", 200);
        String normalizedRole = requireRole(role);
        if (!START_ROLES.contains(normalizedRole)) {
            throw new SecurityException("role is not allowed to start orchestration");
        }
        java.util.Objects.requireNonNull(input, "input");
        OrchestrationDefinition.steps(definitionId);
        String fingerprint = fingerprint(definitionId, input, normalizedRole);
        reconcileWaitingApprovals();
        Instant now = clock.instant();
        OrchestrationRunStore.StartResult result = store.createOrGet(idempotencyKey.trim(), fingerprint, () -> {
            UUID id = UUID.randomUUID();
            List<OrchestrationStep> steps = OrchestrationDefinition.steps(definitionId).stream()
                    .map(OrchestrationStep::pending).toList();
            List<OrchestrationTraceRecord> trace = List.of(new OrchestrationTraceRecord(
                    UUID.randomUUID(), 1L, now, "orchestrator", ExecutionStage.INITIALIZATION,
                    ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "园区异常联合研判已启动"));
            return new OrchestrationRun(id, definitionId, OrchestrationStatus.RUNNING, now, now, null,
                    normalizeActor(requestedBy), normalizedRole, input, "编排已启动", steps, List.of(), id,
                    null, null, idempotencyKey.trim(), fingerprint, false, 0, trace);
        });
        if (result.created()) {
            publishProjection(result.run());
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
        OrchestrationRun run = snapshot(runId);
        if (run.status() == OrchestrationStatus.WAITING_APPROVAL) {
            reconcileApproval(runId);
            run = snapshot(runId);
        }
        return run;
    }

    /** Returns persisted state without reconciling or scheduling work. */
    public OrchestrationRun snapshot(UUID runId) {
        return store.find(runId)
                .orElseThrow(() -> new NoSuchElementException("Unknown orchestration run"));
    }

    public OrchestrationRun cancel(UUID runId) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
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
            OrchestrationRun cancelled = store.update(runId, run -> run.copy(
                    OrchestrationStatus.CANCELLED, run.startedAt(), now, "编排已取消",
                    run.steps().stream().map(step ->
                            step.status() == OrchestrationStepStatus.PENDING
                                    || step.status() == OrchestrationStepStatus.RUNNING
                                    || step.status() == OrchestrationStepStatus.WAITING_APPROVAL
                            ? step.transition(OrchestrationStepStatus.CANCELLED, now, null, null,
                                    null, null, "编排已取消") : step).toList(),
                    run.evidence(), "编排已取消", run.result(), true,
                    appendedTrace(run, "orchestrator", ExecutionStage.COMPLETION,
                            ExecutionEventType.RUN_CANCELLED, ExecutionStatus.INTERRUPTED,
                            "园区异常联合研判已取消")));
            cancelChild(activeType, childReference);
            publishProjection(cancelled);
            return cancelled;
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
                    () -> operations.start(input.question(), () -> cancelled(runId)), operations::cancel);
            if (child == null) return false;
            ChildOutcome outcome = child.completion().join();
            if (cancelled(runId)) return false;
            if (!"COMPLETED".equals(outcome.status()) && !"PARTIAL".equals(outcome.status())) {
                String reason = "NEEDS_CLARIFICATION".equals(outcome.status())
                        ? "运营分析需要澄清，编排未猜测用户选择" : "运营分析未完成";
                if ("NEEDS_CLARIFICATION".equals(outcome.status())) {
                    cancelChild(OrchestrationStepType.OPERATIONS_ANALYSIS, child.runId().toString());
                }
                failStep(runId, step.id(), reason, true);
                return false;
            }
            completeStep(runId, step.id(), safeSummary(outcome.summary(), "运营分析已完成"),
                    outcome.runId().toString(), outcome.evidenceReferences(), List.of(), List.of(),
                    outcome.partialReason());
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
                    null, outcome.evidenceReferences(), outcome.sourceReferences(), outcome.recommendations(),
                    "PARTIAL".equals(outcome.status()) ? "能耗时序存在缺失点" : null);
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
                    () -> collaboration.start(input.question()), collaboration::cancel);
            if (child == null) return false;
            ChildOutcome outcome = child.completion().join();
            if (cancelled(runId)) return false;
            if (!"COMPLETED".equals(outcome.status()) && !"PARTIAL".equals(outcome.status())) {
                failStep(runId, step.id(), "专家协作未完成", false);
                return true;
            }
            completeStep(runId, step.id(), safeSummary(outcome.summary(), "专家协作已完成"),
                    outcome.runId().toString(), outcome.evidenceReferences(), List.of(), List.of(),
                    outcome.partialReason());
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
                    null, outcome.evidenceReferences(), outcome.sourceReferences(), outcome.recommendations(), null);
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

    private boolean reconcileApproval(UUID runId) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            return reconcileApprovalLocked(runId);
        }
    }

    private boolean reconcileApprovalLocked(UUID runId) {
        OrchestrationRun run = getStored(runId);
        if (run.status() != OrchestrationStatus.WAITING_APPROVAL) return false;
        OrchestrationStep step = run.steps().stream()
                .filter(item -> item.status() == OrchestrationStepStatus.WAITING_APPROVAL)
                .findFirst().orElse(null);
        if (step == null) {
            failRun(runId, "等待审批的编排缺少对应步骤");
            return false;
        }
        if (approvalExpired(step, clock.instant())) {
            expireApprovalLocked(runId, step);
            return true;
        }
        if (step.runReference() == null || workflow == null) {
            commitApprovalResume(runId, step.id(), false, "审批子运行无法恢复",
                    step.runReference(), null, "审批子运行无法恢复");
            executor.execute(() -> execute(runId));
            return false;
        }
        WorkflowOutcome outcome;
        try {
            outcome = workflow.get(step.runReference());
        } catch (NoSuchElementException missingChild) {
            commitApprovalResume(runId, step.id(), false, "服务恢复后无法确认审批子运行",
                    step.runReference(), null, "审批子运行不可恢复");
            executor.execute(() -> execute(runId));
            return false;
        }
        if ("WAITING_APPROVAL".equals(outcome.status())) return false;
        boolean completed = "COMPLETED".equals(outcome.status()) || "REJECTED".equals(outcome.status());
        String summary = "REJECTED".equals(outcome.status())
                ? "人工拒绝了处置动作"
                : completed ? "处置工作流已完成" : "处置工作流未完成";
        commitApprovalResume(runId, step.id(), completed, summary, outcome.workflowId(),
                outcome.evidenceReferences(), outcome.approvalResult());
        executor.execute(() -> execute(runId));
        return false;
    }

    /** Reconciles waiting children and terminalizes approvals whose durable deadline elapsed. */
    public int reconcileWaitingApprovals() {
        int expired = 0;
        for (OrchestrationRun run : store.nonTerminalRuns()) {
            if (run.status() != OrchestrationStatus.WAITING_APPROVAL) continue;
            try {
                if (reconcileApproval(run.id())) expired++;
            } catch (RuntimeException failure) {
                LOGGER.warn("Unable to reconcile orchestration approval {}", run.id(), failure);
            }
        }
        return expired;
    }

    private boolean approvalExpired(OrchestrationStep step, Instant now) {
        Instant deadline = step.approvalExpiresAt();
        if (deadline == null) {
            deadline = step.startedAt() == null ? Instant.MIN : step.startedAt().plus(approvalTimeout);
        }
        return !now.isBefore(deadline);
    }

    private void expireApprovalLocked(UUID runId, OrchestrationStep waitingStep) {
        OrchestrationRun current = getStored(runId);
        int firstNewTraceIndex = current.traceEvents().size();
        Instant now = clock.instant();
        OrchestrationRun expired = store.update(runId, run -> {
            List<OrchestrationStep> steps = new ArrayList<>(run.steps());
            int index = stepIndex(steps, waitingStep.id());
            OrchestrationStep step = steps.get(index);
            if (run.status() != OrchestrationStatus.WAITING_APPROVAL
                    || step.status() != OrchestrationStepStatus.WAITING_APPROVAL) {
                return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), steps,
                        run.evidence(), run.failureReason(), run.result(), run.cancelRequested(), run.traceEvents());
            }
            steps.set(index, step.transition(OrchestrationStepStatus.FAILED, now,
                    null, null, null, null, "人工审批等待超时"));
            List<OrchestrationTraceRecord> trace = appendedTrace(run.traceEvents(), step.id(),
                    ExecutionStage.FAILURE, ExecutionEventType.STEP_FAILED,
                    ExecutionStatus.FAILED, "人工审批等待超时");
            trace = appendedTrace(trace, "orchestrator", ExecutionStage.FAILURE,
                    ExecutionEventType.RUN_FAILED, ExecutionStatus.FAILED,
                    "园区异常联合研判因审批超时终止");
            return run.copy(OrchestrationStatus.FAILED, run.startedAt(), now,
                    "编排因审批超时终止", steps, run.evidence(),
                    "人工审批等待超时", run.result(), run.cancelRequested(), trace);
        });
        publishProjections(expired, firstNewTraceIndex);
    }

    private boolean applyWorkflowOutcome(UUID runId, String stepId, WorkflowOutcome outcome) {
        if ("COMPLETED".equals(outcome.status()) || "REJECTED".equals(outcome.status())) {
            String summary = "REJECTED".equals(outcome.status()) ? "人工拒绝了处置动作" : "处置工作流已完成";
            completeStep(runId, stepId, summary, outcome.workflowId(), outcome.evidenceReferences(),
                    List.of(), List.of(), null, outcome.approvalResult());
            return true;
        }
        failStep(runId, stepId, "处置工作流未完成", false);
        return true;
    }

    private void waitForApproval(UUID runId, String stepId, WorkflowOutcome outcome) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status().isTerminal()) return;
            Instant now = clock.instant();
            OrchestrationRun waiting = store.update(runId, run -> {
                List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                int index = stepIndex(steps, stepId);
                steps.set(index, steps.get(index).waitForApproval(now, now.plus(approvalTimeout),
                        "等待现有 Human Approval", outcome.workflowId(), outcome.evidenceReferences()));
                return run.copy(OrchestrationStatus.WAITING_APPROVAL,
                        run.startedAt(), null, "等待人工审批", steps, run.evidence(), null,
                        run.result(), run.cancelRequested(),
                        appendedTrace(run, "orchestrator", ExecutionStage.HUMAN_APPROVAL,
                                ExecutionEventType.WAITING_APPROVAL, ExecutionStatus.RUNNING, "等待人工审批"));
            });
            publishProjection(waiting);
        }
    }

    private void commitApprovalResume(UUID runId, String stepId, boolean completed, String stepSummary,
                                      String workflowId, List<String> evidence, String approvalResult) {
        Instant now = clock.instant();
        List<String> suppliedEvidence = evidence == null ? null : List.copyOf(evidence);
        OrchestrationRun current = getStored(runId);
        int firstNewTraceIndex = current.traceEvents().size();
        OrchestrationRun resumed = store.update(runId, run -> {
            List<OrchestrationStep> steps = new ArrayList<>(run.steps());
            int index = stepIndex(steps, stepId);
            OrchestrationStep currentStep = steps.get(index);
            List<String> resultEvidence = suppliedEvidence == null
                    ? currentStep.evidenceReferences() : suppliedEvidence;
            OrchestrationStep resolved = completed
                    ? currentStep.complete(now, stepSummary, workflowId, resultEvidence,
                            List.of(), List.of(), null)
                    : currentStep.transition(OrchestrationStepStatus.FAILED, now, null, null,
                            workflowId, resultEvidence, stepSummary);
            steps.set(index, resolved.withApprovalResult(approvalResult));
            LinkedHashSet<String> mergedEvidence = new LinkedHashSet<>(run.evidence());
            mergedEvidence.addAll(resultEvidence);
            List<OrchestrationTraceRecord> trace = appendedTrace(run.traceEvents(), "orchestrator",
                    ExecutionStage.HUMAN_APPROVAL, ExecutionEventType.APPROVAL_RESUMED,
                    ExecutionStatus.RUNNING, "人工审批等待已结束");
            trace = appendedTrace(trace, stepId,
                    completed ? stageFor(stepId) : ExecutionStage.FAILURE,
                    completed ? ExecutionEventType.STEP_COMPLETED : ExecutionEventType.STEP_FAILED,
                    completed ? ExecutionStatus.RUNNING : ExecutionStatus.FAILED, stepSummary);
            return run.copy(OrchestrationStatus.RUNNING, run.startedAt(), null,
                    approvalResult == null ? "审批后恢复编排" : approvalResult,
                    steps, List.copyOf(mergedEvidence), null, run.result(), run.cancelRequested(), trace);
        });
        publishProjections(resumed, firstNewTraceIndex);
    }

    private boolean startStep(UUID runId, String stepId, String inputSummary) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status() != OrchestrationStatus.RUNNING) return false;
            int currentIndex = stepIndex(current.steps(), stepId);
            if (current.steps().get(currentIndex).status() != OrchestrationStepStatus.PENDING) return false;
            Instant now = clock.instant();
            OrchestrationRun started = store.update(runId, run -> {
                List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                int index = stepIndex(steps, stepId);
                steps.set(index, steps.get(index).transition(OrchestrationStepStatus.RUNNING,
                        now, inputSummary, null, null, null, null));
                return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), steps,
                        run.evidence(), run.failureReason(), run.result(), run.cancelRequested(),
                        appendedTrace(run, stepId, stageFor(stepId), ExecutionEventType.STEP_STARTED,
                                ExecutionStatus.RUNNING, inputSummary));
            });
            publishProjection(started);
            return true;
        }
    }

    private StartedChild startChild(UUID runId, String stepId, String inputSummary,
                                    Supplier<StartedChild> start,
                                    java.util.function.Consumer<UUID> cancel) {
        if (!startStep(runId, stepId, inputSummary)) return null;
        StartedChild child = start.get();
        if (!rememberChildReference(runId, stepId, child.runId().toString())) {
            cancel.accept(child.runId());
            return null;
        }
        return child;
    }

    private WorkflowOutcome startWorkflow(UUID runId, String stepId, String alertId) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            if (!startStep(runId, stepId, "调用现有 Alert Workflow")) return null;
            WorkflowOutcome outcome = workflow.start(alertId);
            rememberChildReference(runId, stepId, outcome.workflowId());
            return outcome;
        }
    }

    private void completeStep(UUID runId, String stepId, String outputSummary,
                              String childRun, List<String> evidence) {
        completeStep(runId, stepId, outputSummary, childRun, evidence, List.of(), List.of(), null);
    }

    private void completeStep(UUID runId, String stepId, String outputSummary,
                              String childRun, List<String> evidence, List<String> sources,
                              List<String> recommendations, String partialReason) {
        completeStep(runId, stepId, outputSummary, childRun, evidence, sources,
                recommendations, partialReason, null);
    }

    private void completeStep(UUID runId, String stepId, String outputSummary,
                              String childRun, List<String> evidence, List<String> sources,
                              List<String> recommendations, String partialReason, String approvalResult) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            Instant now = clock.instant();
            List<String> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
            List<String> safeSources = sources == null ? List.of() : List.copyOf(sources);
            List<String> safeRecommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
            OrchestrationRun completed = store.update(runId, run -> {
                List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                int index = stepIndex(steps, stepId);
                steps.set(index, steps.get(index).complete(now, outputSummary, childRun,
                        safeEvidence, safeSources, safeRecommendations, partialReason)
                        .withApprovalResult(approvalResult));
                LinkedHashSet<String> mergedEvidence = new LinkedHashSet<>(run.evidence());
                mergedEvidence.addAll(safeEvidence);
                return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), steps,
                        List.copyOf(mergedEvidence), run.failureReason(), run.result(), run.cancelRequested(),
                        appendedTrace(run, stepId, stageFor(stepId), ExecutionEventType.STEP_COMPLETED,
                                ExecutionStatus.RUNNING, outputSummary));
            });
            publishProjection(completed);
        }
    }

    private void skipStep(UUID runId, String stepId, String reason, boolean partial) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            if (getStored(runId).status().isTerminal()) return;
            Instant now = clock.instant();
            OrchestrationRun skipped = store.update(runId, run -> {
                List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                int index = stepIndex(steps, stepId);
                steps.set(index, steps.get(index).transition(OrchestrationStepStatus.SKIPPED,
                        now, null, reason, null, List.of(), partial ? reason : null));
                return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), steps,
                        run.evidence(), run.failureReason(), run.result(), run.cancelRequested(),
                        appendedTrace(run, stepId, stageFor(stepId), ExecutionEventType.STEP_SKIPPED,
                                ExecutionStatus.RUNNING, reason));
            });
            publishProjection(skipped);
        }
    }

    private void failStep(UUID runId, String stepId, String reason, boolean required) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status().isTerminal()) return;
            Instant now = clock.instant();
            if (required) {
                int firstNewTraceIndex = current.traceEvents().size();
                OrchestrationRun failed = store.update(runId, run -> {
                    List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                    int index = stepIndex(steps, stepId);
                    steps.set(index, steps.get(index).transition(OrchestrationStepStatus.BLOCKED,
                            now, null, null, null, null, reason));
                    List<OrchestrationTraceRecord> trace = appendedTrace(run.traceEvents(), stepId,
                            ExecutionStage.FAILURE, ExecutionEventType.STEP_FAILED,
                            ExecutionStatus.FAILED, reason);
                    trace = appendedTrace(trace, "orchestrator", ExecutionStage.FAILURE,
                            ExecutionEventType.RUN_FAILED, ExecutionStatus.FAILED,
                            "园区异常联合研判失败");
                    return run.copy(OrchestrationStatus.FAILED, run.startedAt(), now, "编排失败",
                            steps, run.evidence(), reason, run.result(), run.cancelRequested(), trace);
                });
                publishProjections(failed, firstNewTraceIndex);
                return;
            }
            OrchestrationRun failed = store.update(runId, run -> {
                List<OrchestrationStep> steps = new ArrayList<>(run.steps());
                int index = stepIndex(steps, stepId);
                steps.set(index, steps.get(index).transition(OrchestrationStepStatus.FAILED,
                        now, null, null, null, null, reason));
                return run.copy(run.status(), run.startedAt(), run.completedAt(), run.summary(), steps,
                        run.evidence(), run.failureReason(), run.result(), run.cancelRequested(),
                        appendedTrace(run, stepId, ExecutionStage.FAILURE,
                                ExecutionEventType.STEP_FAILED, ExecutionStatus.FAILED, reason));
            });
            publishProjection(failed);
        }
    }

    private boolean rememberChildReference(UUID runId, String stepId, String childRun) {
        if (childRun == null) return true;
        try (RunLockLease ignored = acquireRunLock(runId)) {
            OrchestrationRun current = store.find(runId).orElse(null);
            if (current == null || current.status().isTerminal()) return false;
            updateStep(runId, stepId, step -> step.transition(step.status(), clock.instant(),
                    null, null, childRun, null, step.failureReason()));
            return true;
        }
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
        try (RunLockLease ignored = acquireRunLock(runId)) {
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
                    .map(OrchestrationStep::approvalResult).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            List<String> recommendations = current.steps().stream()
                    .flatMap(step -> step.recommendations().stream()).distinct().toList();
            if (recommendations.isEmpty()) recommendations = List.of("请根据已列出的证据与未完成项进行人工复核");
            OrchestrationResult result = new OrchestrationResult(conclusion,
                    recommendations, current.evidence(),
                    sourceReferences(current), childRuns, skipped, partialReasons, approval);
            Instant now = clock.instant();
            String terminalSummary = status == OrchestrationStatus.PARTIAL
                    ? "园区异常联合研判部分完成" : "园区异常联合研判完成";
            OrchestrationRun completed = store.update(runId, run -> run.copy(status, run.startedAt(), now,
                    conclusion, run.steps(), run.evidence(), null, result, run.cancelRequested(),
                    appendedTrace(run, "orchestrator", ExecutionStage.COMPLETION,
                            ExecutionEventType.RUN_COMPLETED, ExecutionStatus.SUCCEEDED, terminalSummary)));
            publishProjection(completed);
        }
    }

    private List<String> sourceReferences(OrchestrationRun run) {
        return run.steps().stream().flatMap(step -> step.sourceReferences().stream()).distinct().toList();
    }

    private void failRun(UUID runId, String reason) {
        try (RunLockLease ignored = acquireRunLock(runId)) {
            OrchestrationRun current = getStored(runId);
            if (current.status().isTerminal()) return;
            Instant now = clock.instant();
            OrchestrationRun failed = store.update(runId, run -> run.copy(OrchestrationStatus.FAILED,
                    run.startedAt(), now, "编排失败", run.steps(), run.evidence(),
                    reason, run.result(), run.cancelRequested(),
                    appendedTrace(run, "orchestrator", ExecutionStage.FAILURE,
                            ExecutionEventType.RUN_FAILED, ExecutionStatus.FAILED, "园区异常联合研判失败")));
            publishProjection(failed);
        }
    }

    private List<OrchestrationTraceRecord> appendedTrace(OrchestrationRun run, String actor,
                                                          ExecutionStage stage, ExecutionEventType type,
                                                          ExecutionStatus status, String summary) {
        return appendedTrace(run.traceEvents(), actor, stage, type, status, summary);
    }

    private List<OrchestrationTraceRecord> appendedTrace(List<OrchestrationTraceRecord> current,
                                                          String actor, ExecutionStage stage,
                                                          ExecutionEventType type, ExecutionStatus status,
                                                          String summary) {
        long sequence = current.size() + 1L;
        List<OrchestrationTraceRecord> trace = new ArrayList<>(current);
        trace.add(new OrchestrationTraceRecord(UUID.randomUUID(), sequence, clock.instant(), actor,
                stage, type, status, summary));
        return trace;
    }

    private void publishProjection(OrchestrationRun updated) {
        publishProjections(updated, updated.traceEvents().size() - 1);
    }

    private void publishProjections(OrchestrationRun updated, int firstTraceIndex) {
        for (int index = firstTraceIndex; index < updated.traceEvents().size(); index++) {
            OrchestrationTraceRecord record = updated.traceEvents().get(index);
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
        return store.find(runId).map(run -> run.status() == OrchestrationStatus.CANCELLED).orElse(true);
    }

    private OrchestrationRun getStored(UUID runId) {
        return store.find(runId).orElseThrow(() -> new NoSuchElementException("Unknown orchestration run"));
    }

    int retainedRunLockCount() {
        return runLocks.size();
    }

    private RunLockLease acquireRunLock(UUID runId) {
        RunLock holder = runLocks.compute(runId, (ignored, current) -> {
            RunLock selected = current == null ? new RunLock() : current;
            selected.users++;
            return selected;
        });
        holder.lock.lock();
        return new RunLockLease(runId, holder);
    }

    private final class RunLockLease implements AutoCloseable {
        private final UUID runId;
        private final RunLock holder;
        private boolean closed;

        private RunLockLease(UUID runId, RunLock holder) {
            this.runId = runId;
            this.holder = holder;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            holder.lock.unlock();
            runLocks.computeIfPresent(runId, (ignored, current) -> {
                if (current != holder) return current;
                current.users--;
                boolean terminalOrMissing = store.find(runId)
                        .map(run -> run.status().isTerminal()).orElse(true);
                return current.users == 0 && terminalOrMissing ? null : current;
            });
        }
    }

    private static final class RunLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
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
            updateFingerprint(digest, "orchestration-fingerprint-v1");
            updateFingerprint(digest, definitionId);
            updateFingerprint(digest, role);
            updateFingerprint(digest, input.question());
            updateFingerprint(digest, input.alertId());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(input.buildingIds().size()).array());
            input.buildingIds().forEach(value -> updateFingerprint(digest, value));
            digest.update((byte) (input.energyRelated() ? 1 : 0));
            digest.update((byte) (input.crossDomain() ? 1 : 0));
            digest.update((byte) (input.securityRelated() ? 1 : 0));
            digest.update((byte) (input.requestAction() ? 1 : 0));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateFingerprint(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
