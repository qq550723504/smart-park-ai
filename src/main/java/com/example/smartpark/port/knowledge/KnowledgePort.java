package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeMatch;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(String query);

    /** Ranked search; the legacy search method remains available to existing callers. */
    default List<KnowledgeMatch> rankedSearch(String query) {
        return search(query).stream().map(document -> new KnowledgeMatch(document, 1.0)).toList();
    }
}
