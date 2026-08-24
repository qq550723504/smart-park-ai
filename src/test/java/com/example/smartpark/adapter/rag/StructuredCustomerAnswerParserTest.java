package com.example.smartpark.adapter.rag;

import com.example.smartpark.agent.ModelOutputException;
import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeMatch;
import com.example.smartpark.model.customer.CustomerAnswer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredCustomerAnswerParserTest {
    private final List<KnowledgeMatch> evidence = List.of(new KnowledgeMatch(
            new KnowledgeDocument("KD-PARKING-001", "Parking", "private body", List.of("parking"), Instant.EPOCH), .9));

    @Test void acceptsStrictAnswerWithRetrievedCitation() {
        CustomerAnswer answer = StructuredCustomerAnswerParser.parse("""
                {"answer":"请按停车指引办理。","needsHuman":false,"reason":"SUPPORTED","citationIds":["KD-PARKING-001"]}
                """, evidence);
        assertThat(answer.citationIds()).containsExactly("KD-PARKING-001");
    }

    @Test void rejectsInventedCitationAndExtraFields() {
        assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse("""
                {"answer":"unsupported","needsHuman":false,"reason":"SUPPORTED","citationIds":["KD-MADE-UP"]}
                """, evidence)).isInstanceOf(ModelOutputException.class);
        assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse("""
                {"answer":"x","needsHuman":false,"reason":"SUPPORTED","citationIds":[],"prompt":"secret"}
                """, evidence)).isInstanceOf(ModelOutputException.class);
    }

    @Test void rejectsWrongTypesAndOverlongAnswer() {
        assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse("""
                {"answer":"x","needsHuman":"false","reason":"SUPPORTED","citationIds":[]}
                """, evidence)).isInstanceOf(ModelOutputException.class);
        String json = "{\"answer\":\"" + "x".repeat(2001) + "\",\"needsHuman\":false,\"reason\":\"SUPPORTED\",\"citationIds\":[]}";
        assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse(json, evidence)).isInstanceOf(ModelOutputException.class);
    }

    @Test void rejectsSupportedAnswersWithoutUniqueCitations() {
        assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse("""
                {"answer":"x","needsHuman":false,"reason":"SUPPORTED","citationIds":[]}
                """, evidence)).isInstanceOf(ModelOutputException.class);
        assertThatThrownBy(() -> StructuredCustomerAnswerParser.parse("""
                {"answer":"x","needsHuman":false,"reason":"SUPPORTED","citationIds":["KD-PARKING-001","KD-PARKING-001"]}
                """, evidence)).isInstanceOf(ModelOutputException.class);
    }
}
