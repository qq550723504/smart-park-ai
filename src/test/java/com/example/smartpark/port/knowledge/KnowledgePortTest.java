package com.example.smartpark.port.knowledge;

import com.example.smartpark.model.common.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePortTest {

    @Test
    void defaultRankedSearchFailsClosedWhenAdapterDoesNotProvideScores() {
        KnowledgeDocument document = new KnowledgeDocument(
                "KD-DEFAULT-001", "Default document", "Content", List.of("default"), Instant.EPOCH);
        KnowledgePort port = query -> List.of(document);

        assertThat(port.rankedSearch("default")).singleElement()
                .extracting(KnowledgeMatch::score)
                .isEqualTo(0.0);
    }
}
