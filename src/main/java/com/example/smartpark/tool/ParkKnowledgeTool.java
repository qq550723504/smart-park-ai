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
        String normalizedQuery = requireText(query, "query");
        return new KnowledgeSearchResult(normalizedQuery, knowledgePort.search(normalizedQuery), null, MOCK_NOTICE);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public record KnowledgeSearchResult(String query, List<KnowledgeDocument> documents, String error, String notice) {

        public KnowledgeSearchResult {
            query = requireText(query, "query");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            notice = requireText(notice, "notice");
            if (error != null) {
                error = error.trim();
            }
        }
    }
}
