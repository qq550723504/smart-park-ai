package com.example.smartpark.demo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoFaultInjectorTest {

    @Test
    void injectedFaultIsConsumedOnlyOnce() {
        DemoFaultInjector injector = new DemoFaultInjector();
        injector.inject(new DemoFaultInjector.Fault(DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH));

        assertThatThrownBy(() -> injector.failIfRequested(
                DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> injector.failIfRequested(
                DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH))
                .doesNotThrowAnyException();
    }
}
