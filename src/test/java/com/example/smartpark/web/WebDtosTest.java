package com.example.smartpark.web;

import com.example.smartpark.model.customer.CustomerAnswer;
import com.example.smartpark.model.customer.CustomerServiceResult;
import com.example.smartpark.model.customer.KnowledgeCitation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebDtosTest {
    @Test
    void customerServiceResponseIncludesSafeKnowledgeCitations() {
        CustomerServiceResult result = new CustomerServiceResult(
                "session-1", "PARKING", "停车说明", List.of("Visitor parking guide"),
                List.of(new KnowledgeCitation("KB-PARKING-001", "Visitor parking guide", 0.91)),
                false, null, CustomerAnswer.Reason.SUPPORTED, List.of("KB-PARKING-001"));

        WebDtos.CustomerServiceResponse response = WebDtos.from(result);

        assertThat(response.knowledgeCitations())
                .extracting(WebDtos.KnowledgeCitationResponse::documentId,
                        WebDtos.KnowledgeCitationResponse::title,
                        WebDtos.KnowledgeCitationResponse::score)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("KB-PARKING-001", "Visitor parking guide", 0.91));
    }
}
