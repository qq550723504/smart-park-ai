package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(KnowledgeDomain domain, String query);

    default List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
        return search(domain, query).stream().map(document -> new KnowledgeMatch(document, 0.0)).toList();
    }
}
