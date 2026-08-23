package com.example.smartpark.tool;

import com.example.smartpark.model.KnowledgeDocument;
import com.example.smartpark.park.KnowledgePort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class ParkKnowledgeTool {

    private static final String MOCK_NOTICE = "Mock knowledge data only. Tool outputs do not control real park devices.";

    private final KnowledgePort knowledgePort;

    public ParkKnowledgeTool(KnowledgePort knowledgePort) {
        this.knowledgePort = Objects.requireNonNull(knowledgePort, "knowledgePort");
    }

    @Tool(name = "searchParkKnowledge", description = "Search park knowledge documents by keyword query. Returns matching documents and never fabricates missing knowledge.")
    public KnowledgeSearchResult searchParkKnowledge(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return new KnowledgeSearchResult(normalizedQuery, List.of(), "query must not be blank", MOCK_NOTICE);
        }
        return new KnowledgeSearchResult(normalizedQuery, knowledgePort.search(normalizedQuery), null, MOCK_NOTICE);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record KnowledgeSearchResult(String query, List<KnowledgeDocument> documents, String error, String notice) {

        public KnowledgeSearchResult {
            query = normalize(query);
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                query = requireText(query, "query");
            }
        }
    }
}
