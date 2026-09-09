package com.example.smartpark.orchestration;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.analytics.OperationsAnalysisService;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesDtos;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesQuery;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesService;
import com.example.smartpark.analytics.health.DeviceHealthDtos;
import com.example.smartpark.analytics.health.DeviceHealthService;
import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.operations.OperationsCapabilitiesService;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@Configuration(proxyBeanMethods = false)
public class OrchestrationConfiguration {

    @Bean
    @Qualifier("orchestrationClock")
    Clock orchestrationClock() {
        return Clock.systemUTC();
    }

    @Bean
    OrchestrationRunStore orchestrationRunStore(
            @Value("${smartpark.orchestration.state-file:./data/orchestration/runs.json}") String stateFile,
            @Value("${smartpark.orchestration.max-retained-runs:200}") int maxRetainedRuns,
            @Value("${smartpark.orchestration.max-active-runs:8}") int maxActiveRuns,
            @Value("${smartpark.orchestration.max-run-bytes:131072}") int maxRunBytes) {
        // Spring Boot 4's HTTP stack uses tools.jackson. The graph dependencies
        // still carry com.fasterxml Jackson, so the durable adapter deliberately
        // owns its mapper instead of coupling the two incompatible bean types.
        return new FileOrchestrationRunStore(Path.of(stateFile), new ObjectMapper().findAndRegisterModules(),
                maxRetainedRuns, maxActiveRuns, maxRunBytes);
    }

    @Bean
    ExecutionEventArchive orchestrationTraceArchive(OrchestrationRunStore store,
                                                     ExecutionEventPublisher events) {
        return new OrchestrationTraceArchive(store, events);
    }

    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("orchestrationExecutor")
    ExecutorService orchestrationExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "orchestration-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("orchestrationMaintenanceExecutor")
    ScheduledExecutorService orchestrationMaintenanceExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "orchestration-maintenance");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    OrchestrationService orchestrationService(
            OrchestrationRunStore store,
            OperationsCapabilitiesService capabilityService,
            ObjectProvider<OperationsAnalysisService> operationsProvider,
            ObjectProvider<EnergyTimeSeriesService> energyProvider,
            ObjectProvider<DeviceHealthService> deviceHealthProvider,
            ObjectProvider<ExpertCollaborationService> collaborationProvider,
            ObjectProvider<SecurityIncidentService> securityProvider,
            ObjectProvider<AlertPort> alertProvider,
            ObjectProvider<AlertWorkflow> workflowProvider,
            ExecutionEventPublisher events,
            @Qualifier("orchestrationExecutor") ExecutorService executor,
            @Qualifier("orchestrationClock") Clock clock,
            @Value("${smartpark.orchestration.approval-timeout-seconds:900}") long approvalTimeoutSeconds) {
        OrchestrationPorts.OperationsRunner operations = new OrchestrationPorts.OperationsRunner() {
            @Override
            public OrchestrationPorts.StartedChild start(String question) {
                return start(question, () -> false);
            }

            @Override
            public OrchestrationPorts.StartedChild start(String question, BooleanSupplier cancelled) {
                OperationsAnalysisService service = operationsProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("operations analysis unavailable");
                AnalysisRunStore.RunRecord accepted = service.startWhenAvailable(question, cancelled);
                return new OrchestrationPorts.StartedChild(accepted.runId(),
                        service.await(accepted.runId()).thenApply(OrchestrationConfiguration::operationsOutcome));
            }

            @Override
            public void cancel(UUID runId) {
                OperationsAnalysisService service = operationsProvider.getIfAvailable();
                if (service != null) service.abort(runId);
            }
        };
        OrchestrationPorts.CollaborationRunner collaboration = new OrchestrationPorts.CollaborationRunner() {
            @Override
            public OrchestrationPorts.StartedChild start(String question) {
                ExpertCollaborationService service = collaborationProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("expert collaboration unavailable");
                var accepted = service.start(question);
                return new OrchestrationPorts.StartedChild(accepted.runId(), service.await(accepted.runId())
                        .thenApply(OrchestrationConfiguration::collaborationOutcome));
            }

            @Override
            public void cancel(UUID runId) {
                ExpertCollaborationService service = collaborationProvider.getIfAvailable();
                if (service != null) service.abort(runId);
            }
        };
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override
            public OrchestrationPorts.WorkflowOutcome start(String alertId) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.start(alertId));
            }

            @Override
            public OrchestrationPorts.WorkflowOutcome start(String alertId, java.time.Instant approvalExpiresAt) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.start(alertId, approvalExpiresAt));
            }

            @Override
            public OrchestrationPorts.WorkflowOutcome startOwned(
                    String alertId, java.time.Instant approvalExpiresAt) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.startExclusive(alertId, approvalExpiresAt));
            }

            @Override
            public OrchestrationPorts.WorkflowOutcome get(String workflowId) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.status(workflowId));
            }

            @Override
            public OrchestrationPorts.WorkflowOutcome expireApproval(
                    String workflowId, java.time.Instant approvalExpiresAt) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.expireApproval(workflowId, approvalExpiresAt));
            }

            @Override
            public OrchestrationPorts.WorkflowOutcome cancel(String workflowId) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.cancel(workflowId));
            }

            @Override
            public void releaseRetention(String workflowId) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service != null) service.releaseExclusiveRetention(workflowId);
            }
        };
        return new OrchestrationService(store,
                () -> {
                    var current = capabilityService.snapshot();
                    return new OrchestrationPorts.Capabilities(
                            current.analyticsEnabled() && operationsProvider.getIfAvailable() != null,
                            current.analyticsEnabled() && energyProvider.getIfAvailable() != null,
                            current.collaborationEnabled() && collaborationProvider.getIfAvailable() != null,
                            current.securityIncidentEnabled() && securityProvider.getIfAvailable() != null,
                            workflowProvider.getIfAvailable() != null);
                }, operations,
                buildings -> energyOutcome(energyProvider.getIfAvailable(), buildings),
                alertId -> deviceHealthOutcome(deviceHealthProvider.getIfAvailable(),
                        alertProvider.getIfAvailable(), alertId),
                collaboration,
                input -> securityOutcome(securityProvider.getIfAvailable(), input),
                alertId -> {
                    AlertPort alerts = alertProvider.getIfAvailable();
                    if (alerts == null) throw new IllegalStateException("alert scope validation unavailable");
                    return alerts.getAlert(alertId).buildingId();
                },
                workflow, events, executor, clock, Duration.ofSeconds(approvalTimeoutSeconds));
    }

    @Bean
    ApplicationRunner recoverOrchestrationRuns(
            OrchestrationService service,
            @Qualifier("orchestrationMaintenanceExecutor") ScheduledExecutorService maintenance,
            @Value("${smartpark.orchestration.maintenance-interval-seconds:30}") long intervalSeconds) {
        if (intervalSeconds < 1 || intervalSeconds > 300) {
            throw new IllegalArgumentException("orchestration maintenance interval must be 1..300 seconds");
        }
        return args -> {
            service.recover();
            maintenance.scheduleWithFixedDelay(service::reconcileWaitingApprovals,
                    intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        };
    }

    static OrchestrationPorts.ChildOutcome operationsOutcome(AnalysisRunStore.RunRecord run) {
        List<String> evidence = new ArrayList<>();
        evidence.add("operations-analysis-run:" + run.runId());
        if (run.timeResolution() != null) evidence.add("time-source:" + run.timeResolution().source());
        boolean partial = "COMPLETED".equals(run.status()) && run.truncated();
        return new OrchestrationPorts.ChildOutcome(run.runId(), partial ? "PARTIAL" : run.status(),
                run.summary(), evidence,
                partial ? "运营分析结果已达到查询上限，结论基于截断结果集" : null,
                run.failureStage());
    }

    static OrchestrationPorts.ChildOutcome collaborationOutcome(CollaborationRun run) {
        boolean partial = run.status() == CollaborationRun.RunStatus.COMPLETED
                && run.synthesis() != null
                && run.synthesis().status() != FindingStatus.SUPPORTED;
        String partialReason = null;
        if (partial) {
            String uncertainty = String.join("；", run.synthesis().uncertainties());
            partialReason = uncertainty.isBlank()
                    ? "专家协作证据不足"
                    : "专家协作证据不足：" + uncertainty;
        }
        return new OrchestrationPorts.ChildOutcome(run.runId(), partial ? "PARTIAL" : run.status().name(),
                run.synthesis() == null ? "" : run.synthesis().conclusion(),
                run.synthesis() == null ? List.of() : run.synthesis().evidenceRefs(),
                partialReason, run.error());
    }

    private static OrchestrationPorts.EvidenceOutcome energyOutcome(EnergyTimeSeriesService service,
                                                                     List<String> buildings) {
        if (service == null) throw new IllegalStateException("energy time series unavailable");
        EnergyTimeSeriesDtos.Response response = service.query(new EnergyTimeSeriesQuery(
                "energy_kwh", buildings, null, null, EnergyTimeSeriesQuery.Granularity.HOUR));
        List<String> evidence = response.evidence().stream()
                .map(item -> "energy:" + item.buildingId() + ":" + item.actualPointCount()
                        + "/" + item.expectedPointCount())
                .toList();
        String summary = "已读取 " + response.series().stream().mapToInt(series -> series.points().size()).sum()
                + " 个真实能耗时序点，状态 " + response.status();
        return new OrchestrationPorts.EvidenceOutcome(response.status().name(), summary, evidence,
                List.of(response.source().system() + ":" + response.source().metricDefinition()),
                List.of(), response.status() == EnergyTimeSeriesDtos.Status.UNAVAILABLE ? "无可用能耗数据" : null);
    }

    static OrchestrationPorts.EvidenceOutcome deviceHealthOutcome(DeviceHealthService service,
                                                                  AlertPort alertPort,
                                                                  String alertId) {
        if (service == null || alertPort == null) {
            return new OrchestrationPorts.EvidenceOutcome("UNAVAILABLE", "设备健康能力不可用",
                    List.of(), List.of(), List.of(), "设备健康能力不可用");
        }
        com.example.smartpark.model.alert.Alert authoritativeAlert;
        try {
            authoritativeAlert = alertPort.getAlert(alertId);
        } catch (RuntimeException unavailable) {
            return new OrchestrationPorts.EvidenceOutcome("UNAVAILABLE", "无法验证告警设备身份",
                    List.of(), List.of(), List.of(), "无法验证告警设备身份");
        }
        DeviceHealthDtos.Response response = service.assessByAlertId(alertId).orElse(null);
        if (response == null) {
            return new OrchestrationPorts.EvidenceOutcome("UNAVAILABLE", "告警没有匹配的设备遥测身份",
                    List.of(), List.of(), List.of(), "告警没有匹配的设备遥测身份");
        }
        if (!authoritativeAlert.deviceId().equalsIgnoreCase(response.deviceId())
                || !authoritativeAlert.buildingId().equalsIgnoreCase(response.buildingId())) {
            return new OrchestrationPorts.EvidenceOutcome("UNAVAILABLE", "告警与遥测设备身份不一致",
                    List.of(), List.of(), List.of(), "告警与遥测设备身份不一致");
        }
        boolean telemetryUsable = response.sources().stream().anyMatch(source ->
                !"DEVICE_SNAPSHOT".equals(source.system())
                        && !"ALERT_FACT".equals(source.system())
                        && !"DEVICE_HEALTH".equals(source.system())
                        && source.status() != DeviceHealthDtos.Availability.UNAVAILABLE);
        if (response.availability() == DeviceHealthDtos.Availability.UNAVAILABLE
                || response.healthStatus() == DeviceHealthDtos.HealthStatus.UNKNOWN
                || !telemetryUsable) {
            return new OrchestrationPorts.EvidenceOutcome("UNAVAILABLE", "没有可用设备健康证据",
                    List.of(), response.sources().stream().map(DeviceHealthDtos.Source::system).toList(),
                    List.of(), "没有可用设备健康证据");
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("device-health:" + response.deviceId() + ":" + response.healthStatus());
        response.evidence().forEach(item -> evidence.add(item.reference()));
        return new OrchestrationPorts.EvidenceOutcome(response.availability().name(),
                "设备 " + response.deviceId() + " 健康状态 " + response.healthStatus()
                        + "，证据 " + response.evidence().size() + " 条",
                evidence, response.sources().stream().map(DeviceHealthDtos.Source::system).distinct().toList(),
                List.of(), null);
    }

    private static OrchestrationPorts.EvidenceOutcome securityOutcome(SecurityIncidentService service,
                                                                       OrchestrationInput input) {
        if (service == null) throw new IllegalStateException("security incident unavailable");
        List<SecurityIncident> matched = service.findMatching(input.buildingIds(), input.alertId());
        if (matched.isEmpty()) {
            return new OrchestrationPorts.EvidenceOutcome("UNAVAILABLE", "没有匹配的安全事件证据",
                    List.of(), List.of("security-incident"), List.of(), "没有匹配事件");
        }
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        LinkedHashSet<String> recommendations = new LinkedHashSet<>();
        matched.forEach(incident -> {
            evidence.add("security-incident:" + incident.incidentId());
            incident.evidence().forEach(item -> evidence.add("security-event:" + item.sourceId()));
            recommendations.addAll(incident.recommendations());
        });
        return new OrchestrationPorts.EvidenceOutcome("AVAILABLE", "已关联 " + matched.size() + " 个安全事件",
                List.copyOf(evidence), List.of("security-incident"), List.copyOf(recommendations), null);
    }

    private static OrchestrationPorts.WorkflowOutcome workflowOutcome(WorkflowSnapshot snapshot) {
        List<String> evidence = new ArrayList<>();
        evidence.add("alert-workflow:" + snapshot.workflowId());
        if (snapshot.workOrder() != null) evidence.add("work-order:" + snapshot.workOrder().id());
        String approval = snapshot.approval().map(decision -> decision.decision().name()).orElse(null);
        String status = snapshot.status() == com.example.smartpark.model.common.WorkflowStatus.FAILED
                && snapshot.errors().contains("Approval deadline expired")
                ? "APPROVAL_EXPIRED"
                : snapshot.status() == com.example.smartpark.model.common.WorkflowStatus.FAILED
                    && snapshot.errors().contains("Workflow cancelled by orchestration")
                    ? "CANCELLED" : snapshot.status().name();
        return new OrchestrationPorts.WorkflowOutcome(snapshot.workflowId(), status,
                "告警工作流状态 " + snapshot.status(), evidence, approval,
                snapshot.errors().isEmpty() ? null : "告警工作流未完成",
                snapshot.approvalExpiresAt().orElse(null));
    }
}
