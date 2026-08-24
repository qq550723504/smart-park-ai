package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(KnowledgeDomain domain, String query);

    /** @deprecated Use the domain-aware method so customer and alert knowledge cannot mix. */
    @Deprecated(forRemoval = false)
    default List<KnowledgeDocument> search(String query) {
        return search(KnowledgeDomain.CUSTOMER_SERVICE, query);
    }

    default List<KnowledgeMatch> rankedSearch(KnowledgeDomain domain, String query) {
        return search(domain, query).stream().map(document -> new KnowledgeMatch(document, 0.0)).toList();
    }

    /** @deprecated Compatibility bridge for pre-domain adapters and tests. */
    @Deprecated(forRemoval = false)
    default List<com.example.smartpark.port.knowledge.KnowledgeMatch> rankedSearch(String query) {
        return search(query).stream()
                .map(document -> new com.example.smartpark.port.knowledge.KnowledgeMatch(
                        document.id(), document.title(), 0.0))
                .toList();
    }
}
