package com.example.smartpark.demo;

import java.util.concurrent.atomic.AtomicReference;

public final class DemoFaultInjector {
    private final AtomicReference<Fault> nextFault = new AtomicReference<>();

    public void inject(Fault fault) {
        nextFault.set(fault);
    }

    public void failIfRequested(FaultPoint point) {
        Fault fault = nextFault.get();
        if (fault != null && fault.point() == point && nextFault.compareAndSet(fault, null)) {
            throw new IllegalStateException("Injected demo fault at " + point.name());
        }
    }

    public enum FaultPoint {
        KNOWLEDGE_SEARCH
    }

    public record Fault(FaultPoint point) { }
}
