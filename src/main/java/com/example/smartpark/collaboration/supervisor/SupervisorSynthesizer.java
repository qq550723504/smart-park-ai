package com.example.smartpark.collaboration.supervisor;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Supervisor synthesis is deliberately tool-free and can only consume validated findings. */
public final class SupervisorSynthesizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BeanOutputConverter<SynthesisModelOutput> OUTPUT_CONVERTER =
            new BeanOutputConverter<>(SynthesisModelOutput.class);
    private final SynthesisValidator validator;

    public SupervisorSynthesizer() { this(new SynthesisValidator()); }

    public SupervisorSynthesizer(SynthesisValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Builds the only valid runtime synthesis directly from validated expert
     * findings. A provider call cannot add evidence or author the public
     * conclusion, so asking it to echo these selections only adds failure modes.
     */
    public Synthesis synthesize(SupervisorPlan plan, List<ExpertFinding> findings) {
        Objects.requireNonNull(plan, "plan");
        List<ExpertFinding> safeFindings = List.copyOf(findings);
        if (!safeFindings.stream().map(ExpertFinding::domain).allMatch(plan.selectedDomains()::contains)) {
            throw new IllegalArgumentException("findings contain an unselected domain");
        }
        Set<ExpertDomain> selectedDomains = safeFindings.stream()
                .filter(finding -> finding.status() == FindingStatus.SUPPORTED)
                .map(ExpertFinding::domain)
                .collect(Collectors.toUnmodifiableSet());
        FindingStatus status = selectedDomains.isEmpty()
                ? FindingStatus.INSUFFICIENT_EVIDENCE : FindingStatus.SUPPORTED;
        List<String> evidenceRefs = safeFindings.stream()
                .filter(finding -> selectedDomains.contains(finding.domain()))
                .flatMap(finding -> finding.evidenceRefs().stream())
                .distinct()
                .toList();
        double confidence = status == FindingStatus.SUPPORTED
                ? safeFindings.stream()
                .filter(finding -> selectedDomains.contains(finding.domain()))
                .mapToDouble(ExpertFinding::confidence)
                .min().orElse(0.0)
                : 0.0;
        List<String> uncertainties = safeFindings.stream()
                .filter(finding -> finding.status() != FindingStatus.SUPPORTED)
                .sorted(Comparator.comparing(ExpertFinding::domain))
                .map(finding -> finding.domain() + ": " + finding.conclusion())
                .toList();
        Synthesis synthesis = new Synthesis(status,
                deterministicConclusion(status, selectedDomains, safeFindings),
                evidenceRefs, confidence, uncertainties);
        return validator.validate(synthesis, safeFindings, selectedDomains);
    }

    /**
     * Lets the supervisor make the cross-domain decision after the server has
     * validated every expert finding. The model receives findings only, never
     * tool callbacks or raw tool payloads, and the returned references/status
     * are still checked against the validated finding set.
     */
    public Synthesis synthesize(ChatModel model, SupervisorPlan plan, List<ExpertFinding> findings) {
        Objects.requireNonNull(model, "model");
        String system = "You are the tool-free supervisor for park collaboration. "
                + "Decide the cross-domain relationship from the validated expert findings below. "
                + "Answer the user's relationship question directly in conclusion, with an explicit "
                + "decision: 有关联、无关联 or 无法确认. Use only facts present in the findings; do not "
                + "invent facts, expose raw tool payloads, or return independent domain summaries instead "
                + "of the relationship decision. Return only JSON with status, selectedDomains, evidenceRefs, "
                + "confidence, conclusion, uncertainties. If status is SUPPORTED, select every SUPPORTED "
                + "finding and copy exactly its evidence references. If status is INSUFFICIENT_EVIDENCE or "
                + "FAILED, selectedDomains and evidenceRefs must both be empty and confidence must be 0."
                + OUTPUT_CONVERTER.getFormat();
        ChatResponse response = model.call(new Prompt(
                List.of(new SystemMessage(system), new UserMessage("plan=" + plan + "\nfindings=" + findings)),
                synthesisProviderOptions()));
        return parseAndValidate(extract(response), plan, findings, true);
    }

    public Synthesis parseAndValidate(String modelJson, SupervisorPlan plan, List<ExpertFinding> findings) {
        return parseAndValidate(modelJson, plan, findings, false);
    }

    private Synthesis parseAndValidate(String modelJson, SupervisorPlan plan,
                                       List<ExpertFinding> findings, boolean modelConclusion) {
        Objects.requireNonNull(plan, "plan");
        List<ExpertFinding> safeFindings = List.copyOf(findings);
        if (!safeFindings.stream().map(ExpertFinding::domain).allMatch(plan.selectedDomains()::contains)) {
            throw new IllegalArgumentException("findings contain an unselected domain");
        }
        try {
            JsonNode root = JSON.readTree(modelJson);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("synthesis must be a JSON object");
            FindingStatus status = FindingStatus.valueOf(required(root, "status").toUpperCase());
            Set<ExpertDomain> modelSelectedDomains = domains(root.get("selectedDomains"));
            if (status == FindingStatus.SUPPORTED && !plan.selectedDomains().containsAll(modelSelectedDomains)) {
                throw new IllegalArgumentException("synthesis selected a domain outside the supervisor plan");
            }
            Set<ExpertDomain> selectedDomains = status == FindingStatus.SUPPORTED
                    ? modelSelectedDomains : Set.of();
            double modelConfidence = root.path("confidence").asDouble(Double.NaN);
            if (!Double.isFinite(modelConfidence) || modelConfidence < 0 || modelConfidence > 1) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
            String conclusion = modelConclusion
                    ? required(root, "conclusion")
                    : deterministicConclusion(status, selectedDomains, safeFindings);
            double derivedConfidence = status == FindingStatus.SUPPORTED
                    ? safeFindings.stream()
                    .filter(finding -> selectedDomains.contains(finding.domain()))
                    .mapToDouble(ExpertFinding::confidence)
                    .min()
                    .orElse(0.0)
                    : 0.0;
            List<String> uncertainties = strings(root.get("uncertainties"));
            if (uncertainties.isEmpty() && safeFindings.stream()
                    .anyMatch(finding -> finding.status() != FindingStatus.SUPPORTED)) {
                uncertainties = safeFindings.stream()
                        .filter(finding -> finding.status() != FindingStatus.SUPPORTED)
                        .sorted(Comparator.comparing(ExpertFinding::domain))
                        .map(finding -> finding.domain() + ": " + finding.conclusion())
                        .toList();
            }
            Synthesis synthesis = new Synthesis(
                    status, conclusion, status == FindingStatus.SUPPORTED ? strings(root.get("evidenceRefs")) : List.of(),
                    derivedConfidence, uncertainties);
            return modelConclusion
                    ? validator.validateModelSynthesis(synthesis, safeFindings, selectedDomains)
                    : validator.validate(synthesis, safeFindings, selectedDomains);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid synthesis JSON", ex);
        }
    }

    private static String required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " must be non-empty");
        return value.asText().trim();
    }

    private static DashScopeChatOptions synthesisProviderOptions() {
        DashScopeResponseFormat.JsonSchemaConfig schema = DashScopeResponseFormat.JsonSchemaConfig.builder()
                .name("collaboration_supervisor_synthesis")
                .description("Strict structured output for the cross-domain collaboration decision")
                .schema(OUTPUT_CONVERTER.getJsonSchemaMap())
                .strict(true)
                .build();
        return DashScopeChatOptions.builder()
                .responseFormat(DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_SCHEMA)
                        .jsonScheme(schema)
                        .build())
                .build();
    }

    private static String extract(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("collaboration synthesis model response was blank");
        }
        return response.getResult().getOutput().getText();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("synthesis list field must be an array");
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("synthesis lists require non-empty strings");
            values.add(item.asText().trim());
        }
        return List.copyOf(values);
    }

    private static Set<ExpertDomain> domains(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("selectedDomains must be an array");
        }
        EnumSet<ExpertDomain> domains = EnumSet.noneOf(ExpertDomain.class);
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException("selectedDomains require non-empty strings");
            }
            ExpertDomain domain = ExpertDomain.valueOf(item.asText().trim().toUpperCase());
            if (!domains.add(domain)) {
                throw new IllegalArgumentException("selectedDomains must not contain duplicates");
            }
        }
        return Set.copyOf(domains);
    }

    private static String deterministicConclusion(FindingStatus status,
                                                  Set<ExpertDomain> selectedDomains,
                                                  List<ExpertFinding> findings) {
        String conclusion = findings.stream()
                .filter(finding -> selectedDomains.contains(finding.domain()))
                .filter(finding -> finding.status() == FindingStatus.SUPPORTED)
                .sorted(Comparator.comparing(ExpertFinding::domain))
                .map(ExpertFinding::conclusion)
                .collect(Collectors.joining("；"));
        if (!conclusion.isBlank()) {
            return conclusion;
        }
        return status == FindingStatus.FAILED ? "专家协作失败" : "没有可验证的专家结论";
    }

    private record SynthesisModelOutput(
            FindingStatus status,
            List<ExpertDomain> selectedDomains,
            List<String> evidenceRefs,
            double confidence,
            String conclusion,
            List<String> uncertainties) { }
}
