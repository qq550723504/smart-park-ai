package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(String query);

    default List<KnowledgeMatch> rankedSearch(String query) {
        return search(query).stream()
                .map(document -> new KnowledgeMatch(document.id(), document.title(), 1.0))
                .toList();
    }
}
