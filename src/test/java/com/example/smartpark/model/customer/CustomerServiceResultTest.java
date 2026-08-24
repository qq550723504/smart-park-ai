package com.example.smartpark.model.customer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerServiceResultTest {

    @Test
    void rejectsUnsafeCitationIdsEvenWhenConstructedOutsideKnowledgeMatch() {
        assertThatThrownBy(() -> new CustomerServiceResult(
                "cs-1", "PARKING", "supported answer", List.of("Safe title"), false, null,
                "SUPPORTED", List.of("KD\nPRIVATE")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
