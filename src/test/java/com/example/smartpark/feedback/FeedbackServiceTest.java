package com.example.smartpark.feedback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackServiceTest {

    @Test
    void recordsSafeFeedbackAndCountsPositiveRatings() {
        FeedbackService service = new FeedbackService();

        service.record("CUSTOMER_SESSION", "cs-1", FeedbackRating.HELPFUL, "CUSTOMER_AGENT");
        service.record("CUSTOMER_SESSION", "cs-2", FeedbackRating.NOT_HELPFUL, "CUSTOMER_AGENT");

        assertThat(service.entries()).hasSize(2);
        assertThat(service.positiveCount()).isEqualTo(1);
        assertThat(service.entries().get(0).toString()).doesNotContain("身份证", "原始问题");
    }

    @Test
    void freeFormFeedbackIsNotAccepted() {
        FeedbackService service = new FeedbackService();

        assertThatThrownBy(() -> service.record("CUSTOMER_SESSION", "cs-1", null, "CUSTOMER_AGENT"))
                .isInstanceOf(NullPointerException.class);
    }
}
