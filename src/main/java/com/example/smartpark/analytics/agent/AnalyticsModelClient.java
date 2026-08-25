package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured boundary to the language model. Implementations must parse the
 * model output into typed records strictly — free-text SQL extraction is not
 * allowed anywhere downstream.
 */
public interface AnalyticsModelClient {

    QuestionUnderstanding understandQuestion(String question);

    /** Returns a raw SQL draft; must be a single SELECT statement text. */
    String generateSql(SqlGenerationRequest request);

    ChartSpec.Proposal proposeChart(ChartContext context);

    String summarize(SummaryContext context);

    record QuestionUnderstanding(
            String normalizedQuestion,
            List<String> metricTerms,
            List<String> clarificationQuestions,
            RequestedTimeRange requestedTimeRange,
            List<String> requestedDimensions,
            Map<String, String> requestedFilters) {

        public QuestionUnderstanding(String normalizedQuestion,
                                     List<String> metricTerms,
                                     List<String> clarificationQuestions) {
            this(normalizedQuestion, metricTerms, clarificationQuestions, null, List.of(), Map.of());
        }

        public QuestionUnderstanding(String normalizedQuestion,
                                     List<String> metricTerms,
                                     List<String> clarificationQuestions,
                                     RequestedTimeRange requestedTimeRange) {
            this(normalizedQuestion, metricTerms, clarificationQuestions, requestedTimeRange, List.of(), Map.of());
        }

        public QuestionUnderstanding(String normalizedQuestion,
                                     List<String> metricTerms,
                                     List<String> clarificationQuestions,
                                     RequestedTimeRange requestedTimeRange,
                                     List<String> requestedDimensions) {
            this(normalizedQuestion, metricTerms, clarificationQuestions,
                    requestedTimeRange, requestedDimensions, Map.of());
        }

        public QuestionUnderstanding {
            normalizedQuestion = normalizedQuestion == null ? "" : normalizedQuestion.strip();
            metricTerms = List.copyOf(metricTerms == null ? List.of() : metricTerms);
            clarificationQuestions = List.copyOf(clarificationQuestions == null ? List.of() : clarificationQuestions);
            requestedDimensions = List.copyOf(requestedDimensions == null ? List.of() : requestedDimensions);
            requestedFilters = Map.copyOf(requestedFilters == null ? Map.of() : requestedFilters);
        }

        public boolean needsClarification() {
            return !clarificationQuestions.isEmpty();
        }
    }

    record RequestedTimeRange(Instant fromInclusive, Instant toExclusive) {

        public RequestedTimeRange {
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("fromInclusive must be before toExclusive");
            }
        }
    }

    record SqlGenerationRequest(
            QueryPlan plan,
            String schemaDescription,
            String rejectionReason) {}

    record ChartContext(String question, QueryPlan plan, TabularResult result) {}

    record SummaryContext(String question, QueryPlan plan, TabularResult result, ChartSpec chart) {}
}
