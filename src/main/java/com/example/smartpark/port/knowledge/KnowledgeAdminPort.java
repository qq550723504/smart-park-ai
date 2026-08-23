package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;

import java.util.List;

public interface KnowledgeAdminPort extends KnowledgePort {
    List<ManagedDocument> list();
    KnowledgeDocument save(KnowledgeDocument document);
    ManagedDocument setActive(String documentId, boolean active);

    record ManagedDocument(KnowledgeDocument document, boolean active) { }
}
