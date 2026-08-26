package com.example.smartpark.analytics.agent;

import java.util.List;
import java.util.Objects;

/**
 * Reconciles deterministic whitelist evidence with LLM mention evidence.
 * The model is an independent omission detector, never a time-value
 * authority: it can only escalate (UNSUPPORTED/AMBIGUOUS), never override
 * a resolved range or invent one. Matrix per design section 7.4/9:
 *
 * <ul>
 *   <li>parser resolved + model empty or all mentions matched → parser result</li>
 *   <li>both found nothing → NONE</li>
 *   <li>model named time the whitelist cannot resolve → UNSUPPORTED</li>
 *   <li>mention straddling parser mentions or sticking out of them → AMBIGUOUS</li>
 *   <li>multiple distinct ranges → MULTIPLE</li>
 *   <li>equivalent duplicates → deduplicated PARSED</li>
 * </ul>
 */
final class TimeEvidenceReconciler {

    TimeIntentResult reconcile(TimeIntentResult parserResult,
                               List<String> requestedMentions,
                               String question) {
        Objects.requireNonNull(parserResult, "parserResult");
        ModelTimeEvidence model;
        try {
            model = ModelTimeEvidence.fromQuestion(requestedMentions, question);
        } catch (IllegalArgumentException invalidModelMention) {
            return unsupported(List.of(),
                    "模型返回的时间表达与原文不一致: " + invalidModelMention.getMessage());
        }

        if (model.isEmpty()) {
            // Parser alone decides; its own fail-closed statuses stand.
            return parserResult;
        }
        if (parserResult.status() == TimeIntentResult.Status.NONE) {
            return unsupported(model.mentions(),
                    "问题包含暂不支持的时间范围表达式，请换个说法（例如具体日期或“过去N天”）");
        }

        // Every located model span must sit inside one parser mention span.
        // Exact equality or a nested verbatim fragment (“周一” within a wider
        // expression the parser consumed as one candidate) counts as agreement;
        // anything straddling two parser mentions or extending beyond one
        // escalates to AMBIGUOUS rather than being silently accepted.
        for (TimeIntentResult.TimeMention modelMention : model.mentions()) {
            boolean matched = parserResult.mentions().stream().anyMatch(parsed ->
                    parsed.start() <= modelMention.start()
                            && parsed.end() >= modelMention.end()
                            && parsed.text().contains(modelMention.text()));
            if (!matched) {
                return new TimeIntentResult(TimeIntentResult.Status.AMBIGUOUS,
                        parserResult.mentions(), null, null,
                        "模型识别的时间片段与可解析的时间表达不一致: " + modelMention.text());
            }
        }
        return parserResult;
    }

    private static TimeIntentResult unsupported(
            List<TimeIntentResult.TimeMention> mentions, String reason) {
        return new TimeIntentResult(TimeIntentResult.Status.UNSUPPORTED,
                mentions, null, null, reason);
    }
}
