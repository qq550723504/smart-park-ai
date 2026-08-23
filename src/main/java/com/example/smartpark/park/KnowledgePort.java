package com.example.smartpark.park;

import com.example.smartpark.model.KnowledgeDocument;

import java.util.List;

public interface KnowledgePort {
    List<KnowledgeDocument> search(String query);
}
