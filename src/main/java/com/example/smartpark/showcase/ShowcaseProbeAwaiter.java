package com.example.smartpark.showcase;

import java.util.function.Function;
import java.util.function.Supplier;

final class ShowcaseProbeAwaiter {

    private static final long POLL_INTERVAL_MILLIS = 200;

    <T> ShowcaseProbeResult await(Supplier<T> currentRun,
                                  Function<T, ShowcaseProbeResult> terminalMapper) {
        while (true) {
            ShowcaseProbeResult result = terminalMapper.apply(currentRun.get());
            if (result != null) {
                return result;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ShowcaseProbeResult.FAILED;
            }
        }
    }
}
