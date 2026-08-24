package com.example.smartpark.adapter.rag;

import com.example.smartpark.model.common.KnowledgeDocument;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class RagKnowledgeConfiguration {

    @Bean
    @ConditionalOnProperty(name = "smartpark.knowledge.mode", havingValue = "rag")
    RagKnowledgeAdapter ragKnowledgeAdapter(
            EmbeddingModel embeddingModel,
            List<KnowledgeDocument> seedDocuments,
            @Value("${smartpark.knowledge.min-similarity-score:0.65}") double minSimilarityScore) {
        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        return new RagKnowledgeAdapter(vectorStore, seedDocuments, minSimilarityScore);
    }
}
