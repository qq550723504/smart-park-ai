package com.example.smartpark.showcase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.smartpark.agent.AlertModelFailureStage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AlertPreflightBoundaryObserverTest {

    @Test
    void logsOnlyTheAllowlistedFailureStage() {
        Logger logger = (Logger) LoggerFactory.getLogger(AlertPreflightBoundaryObserver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AlertPreflightBoundaryObserver().accept(AlertModelFailureStage.PROVIDER_CALL);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("alert preflight failed: stage=PROVIDER_CALL");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
