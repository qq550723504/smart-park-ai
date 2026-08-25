package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Dynamic fan-out runtime. Only selected experts receive work and selected branches execute concurrently. */
public final class ExpertCollaborationGraph {
    private final Map<ExpertDomain, Expert> experts;
    private final Executor executor;

    public ExpertCollaborationGraph(Map<ExpertDomain, Expert> experts, Executor executor) {
        EnumMap<ExpertDomain, Expert> copy = new EnumMap<>(ExpertDomain.class);
        copy.putAll(Objects.requireNonNull(experts, "experts"));
        if (!copy.keySet().containsAll(java.util.Set.of(ExpertDomain.values()))) {
            throw new IllegalArgumentException("all expert implementations are required");
        }
        this.experts = Map.copyOf(copy);
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public List<ExpertFinding> execute(SupervisorPlan plan) {
        List<CompletableFuture<ExpertFinding>> futures = plan.selectedDomains().stream()
                .map(domain -> CompletableFuture.supplyAsync(() -> invoke(domain, plan.assignments().get(domain)), executor))
                .toList();
        return futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparing(ExpertFinding::domain)).toList();
    }

    private ExpertFinding invoke(ExpertDomain domain, String assignment) {
        try { return experts.get(domain).analyze(assignment); }
        catch (RuntimeException ex) {
            return new ExpertFinding(domain, FindingStatus.FAILED, "failed to analyze expert assignment",
                    List.of(), 0, List.of("retry " + domain.name().toLowerCase() + " expert"));
        }
    }

    @FunctionalInterface
    public interface Expert {
        ExpertFinding analyze(String assignment);
    }
}
