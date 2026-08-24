package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;
import com.example.smartpark.model.common.KnowledgeDomain;
import com.example.smartpark.model.common.KnowledgeMatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePortTest {

    @Test
    void defaultRankedSearchFailsClosedWhenAdapterDoesNotProvideScores() {
        KnowledgeDocument document = new KnowledgeDocument(
                "KD-DEFAULT-001", KnowledgeDomain.CUSTOMER_SERVICE, "Default document", "Content", List.of("default"), Instant.EPOCH);
        KnowledgePort port = (domain, query) -> List.of(document);

        assertThat(port.rankedSearch(KnowledgeDomain.CUSTOMER_SERVICE, "default")).singleElement()
                .extracting(KnowledgeMatch::score)
                .isEqualTo(0.0);
    }
}
