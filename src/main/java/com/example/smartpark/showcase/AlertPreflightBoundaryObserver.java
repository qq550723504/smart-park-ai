package com.example.smartpark.showcase;

import com.example.smartpark.agent.AlertModelFailureStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

final class AlertPreflightBoundaryObserver implements Consumer<AlertModelFailureStage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertPreflightBoundaryObserver.class);

    @Override
    public void accept(AlertModelFailureStage stage) {
        LOGGER.warn("alert preflight failed: stage={}", Objects.requireNonNull(stage, "stage"));
    }
}
