package com.example.smartpark.voice;

import com.example.smartpark.voice.model.AnswerRejectReason;
import com.example.smartpark.voice.model.AnswerValidationException;
import com.example.smartpark.voice.model.ToolCallRecord;
import com.example.smartpark.voice.model.VoiceAnswer;
import com.example.smartpark.voice.model.VoiceIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence gate between streaming LLM output and TTS. Every number, identifier
 * and policy citation must trace back to this turn's real tool evidence; a turn
 * with no tool calls may not contain data claims at all. Failures end the turn
 * explicitly — there is no "plausible guess" fallback.
 */
@Component
public class VoiceAnswerValidator {

    /** Fixture-style identifiers: ALT-TEMP-001, DEV-HVAC-001, KD-PARKING-001, SEC-ACCESS-001. */
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Z]{2,10}-[A-Z0-9]+(?:-[A-Z0-9]+)*");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern POLICY_CITATION = Pattern.compile("\\[doc:([A-Za-z0-9\\-]+)]");

    public void validate(VoiceIntent intent, VoiceAnswer answer) {
        if (answer.text().isBlank()) {
            throw fail(AnswerRejectReason.EMPTY_ANSWER);
        }

        String corpus = corpus(answer);

        if (intent == VoiceIntent.PARKING_POLICY) {
            // 政策类回答先做引用校验，避免引用编号被当作普通标识符误报。
            Matcher citations = POLICY_CITATION.matcher(answer.text());
            boolean anyCitation = false;
            while (citations.find()) {
                anyCitation = true;
                if (!answer.evidenceRefs().contains(citations.group(1))) {
                    throw fail(AnswerRejectReason.UNKNOWN_POLICY_CITATION);
                }
            }
            if (!anyCitation) {
                throw fail(AnswerRejectReason.MISSING_POLICY_CITATION);
            }
        }

        for (Matcher ids = IDENTIFIER.matcher(answer.text()); ids.find(); ) {
            if (!corpus.contains(ids.group())) {
                throw fail(AnswerRejectReason.UNSUPPORTED_CLAIM_IDENTIFIER);
            }
        }

        if (answer.toolCalls().isEmpty()) {
            // No tool evidence this turn: any number would be an invented claim.
            if (NUMBER.matcher(answer.text()).find()) {
                throw fail(AnswerRejectReason.UNSUPPORTED_CLAIM_NUMBER);
            }
        } else {
            for (Matcher numbers = NUMBER.matcher(answer.text()); numbers.find(); ) {
                if (!corpus.contains(numbers.group())) {
                    throw fail(AnswerRejectReason.UNSUPPORTED_CLAIM_NUMBER);
                }
            }
        }
    }

    private static String corpus(VoiceAnswer answer) {
        StringBuilder builder = new StringBuilder();
        for (ToolCallRecord call : answer.toolCalls()) {
            builder.append(call.argumentSummary()).append(' ').append(call.resultDigest()).append(' ');
        }
        List.copyOf(answer.evidenceRefs()).forEach(ref -> builder.append(ref).append(' '));
        return builder.toString();
    }

    private static AnswerValidationException fail(AnswerRejectReason reason) {
        return new AnswerValidationException(reason);
    }
}
