package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(String query);
}
