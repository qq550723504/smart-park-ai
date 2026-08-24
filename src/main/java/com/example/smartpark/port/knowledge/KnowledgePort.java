package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(String query);

    default List<KnowledgeMatch> rankedSearch(String query) {
        return search(query).stream()
                // An adapter that cannot provide a relevance score must fail closed.
                .map(document -> new KnowledgeMatch(document.id(), document.title(), 0.0))
                .toList();
    }
}
