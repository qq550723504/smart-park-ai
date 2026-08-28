package com.example.smartpark.analytics.agent;

import java.util.List;
import java.util.Objects;

/**
 * Reconciles deterministic parser evidence with LLM mention evidence.
 * The model is an independent omission detector, never a time-value
 * authority: it can only escalate (UNSUPPORTED/AMBIGUOUS), never override
 * a resolved range or invent one. Matrix per design section 7.4/9:
 *
 * <ul>
 *   <li>parser resolved + model empty or all mentions matched → parser result</li>
 *   <li>both found nothing → NONE</li>
 *   <li>model named time the parser cannot resolve → UNSUPPORTED</li>
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

        // Every located model span must exactly equal one parser mention span.
        // Prefixes, suffixes, nested fragments, and spans straddling two parser
        // mentions are all ambiguous rather than silently accepted.
        for (TimeIntentResult.TimeMention modelMention : model.mentions()) {
            boolean matched = parserResult.mentions().stream().anyMatch(parsed ->
                    parsed.start() == modelMention.start()
                            && parsed.end() == modelMention.end()
                            && parsed.text().equals(modelMention.text()));
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
