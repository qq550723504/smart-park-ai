package com.example.smartpark.voice;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Schedules turn deadlines; tests substitute a manual deterministic impl. */
@FunctionalInterface
public interface DeadlineScheduler {

    Cancelable schedule(Runnable task, Duration delay);

    interface Cancelable {
        void cancel();
    }

    /** Daemon-pool based production scheduler. */
    final class ExecutorBacked implements DeadlineScheduler {

        private final ScheduledExecutorService executor;

        public ExecutorBacked() {
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "voice-turn-deadlines");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public Cancelable schedule(Runnable task, Duration delay) {
            ScheduledFuture<?> future =
                    executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    }
}
