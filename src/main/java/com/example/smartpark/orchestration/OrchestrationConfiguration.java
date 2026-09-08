package com.example.smartpark.orchestration;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.analytics.OperationsAnalysisService;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesDtos;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesQuery;
import com.example.smartpark.analytics.energy.EnergyTimeSeriesService;
import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.operations.OperationsCapabilitiesService;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
public class OrchestrationConfiguration {

    @Bean
    @Qualifier("orchestrationClock")
    Clock orchestrationClock() {
        return Clock.systemUTC();
    }

    @Bean
    OrchestrationRunStore orchestrationRunStore(
            @Value("${smartpark.orchestration.state-file:./data/orchestration/runs.json}") String stateFile) {
        // Spring Boot 4's HTTP stack uses tools.jackson. The graph dependencies
        // still carry com.fasterxml Jackson, so the durable adapter deliberately
        // owns its mapper instead of coupling the two incompatible bean types.
        return new FileOrchestrationRunStore(Path.of(stateFile), new ObjectMapper().findAndRegisterModules());
    }

    @Bean
    ExecutionEventArchive orchestrationTraceArchive(OrchestrationRunStore store) {
        return new OrchestrationTraceArchive(store);
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

    @Bean
    OrchestrationService orchestrationService(
            OrchestrationRunStore store,
            OperationsCapabilitiesService capabilityService,
            ObjectProvider<OperationsAnalysisService> operationsProvider,
            ObjectProvider<EnergyTimeSeriesService> energyProvider,
            ObjectProvider<ExpertCollaborationService> collaborationProvider,
            ObjectProvider<SecurityIncidentService> securityProvider,
            ObjectProvider<AlertWorkflow> workflowProvider,
            ExecutionEventPublisher events,
            @Qualifier("orchestrationExecutor") ExecutorService executor,
            @Qualifier("orchestrationClock") Clock clock) {
        OrchestrationPorts.OperationsRunner operations = new OrchestrationPorts.OperationsRunner() {
            @Override
            public OrchestrationPorts.StartedChild start(String question) {
                OperationsAnalysisService service = operationsProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("operations analysis unavailable");
                AnalysisRunStore.RunRecord accepted = service.start(question);
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
                        .thenApply(run -> new OrchestrationPorts.ChildOutcome(
                                run.runId(), run.status().name(),
                                run.synthesis() == null ? "" : run.synthesis().conclusion(),
                                run.synthesis() == null ? List.of() : run.synthesis().evidenceRefs(), run.error())));
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
            public OrchestrationPorts.WorkflowOutcome get(String workflowId) {
                AlertWorkflow service = workflowProvider.getIfAvailable();
                if (service == null) throw new IllegalStateException("alert workflow unavailable");
                return workflowOutcome(service.status(workflowId));
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
                collaboration,
                input -> securityOutcome(securityProvider.getIfAvailable(), input),
                workflow, events, executor, clock);
    }

    @Bean
    ApplicationRunner recoverOrchestrationRuns(OrchestrationService service) {
        return args -> service.recover();
    }

    private static OrchestrationPorts.ChildOutcome operationsOutcome(AnalysisRunStore.RunRecord run) {
        List<String> evidence = new ArrayList<>();
        evidence.add("operations-analysis-run:" + run.runId());
        if (run.timeResolution() != null) evidence.add("time-source:" + run.timeResolution().source());
        return new OrchestrationPorts.ChildOutcome(run.runId(), run.status(), run.summary(), evidence,
                run.failureStage());
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
        return new OrchestrationPorts.WorkflowOutcome(snapshot.workflowId(), snapshot.status().name(),
                "告警工作流状态 " + snapshot.status(), evidence, approval,
                snapshot.errors().isEmpty() ? null : "告警工作流未完成");
    }
}
