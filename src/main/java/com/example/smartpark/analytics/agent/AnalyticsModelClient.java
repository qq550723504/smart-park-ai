package com.example.smartpark.analytics.agent;

import com.example.smartpark.analytics.model.ChartSpec;
import com.example.smartpark.analytics.model.QueryPlan;
import com.example.smartpark.analytics.model.TabularResult;

import java.util.List;

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
            List<String> clarificationQuestions) {

        public QuestionUnderstanding {
            normalizedQuestion = normalizedQuestion == null ? "" : normalizedQuestion.strip();
            metricTerms = List.copyOf(metricTerms == null ? List.of() : metricTerms);
            clarificationQuestions = List.copyOf(clarificationQuestions == null ? List.of() : clarificationQuestions);
        }

        public boolean needsClarification() {
            return !clarificationQuestions.isEmpty();
        }
    }

    record SqlGenerationRequest(
            QueryPlan plan,
            String schemaDescription,
            String rejectionReason) {}

    record ChartContext(String question, QueryPlan plan, TabularResult result) {}

    record SummaryContext(String question, QueryPlan plan, TabularResult result, ChartSpec chart) {}
}
