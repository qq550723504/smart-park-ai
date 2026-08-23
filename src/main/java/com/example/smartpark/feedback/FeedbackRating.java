package com.example.smartpark.feedback;

public enum FeedbackRating {
    HELPFUL,
    NOT_HELPFUL,
    CORRECT,
    INCORRECT;

    public boolean positive() {
        return this == HELPFUL || this == CORRECT;
    }
}
