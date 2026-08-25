package com.example.smartpark.collaboration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Runtime limits for dynamic expert fan-out and the overall collaboration run. */
@ConfigurationProperties(prefix = "smartpark.collaboration")
@Validated
public class ExpertCollaborationProperties {
    private Duration expertTimeout = Duration.ofSeconds(15);
    private Duration runTimeout = Duration.ofSeconds(40);

    @Min(1)
    @Max(3)
    private int maxParallel = 3;

    public Duration getExpertTimeout() { return expertTimeout; }
    public void setExpertTimeout(Duration expertTimeout) { this.expertTimeout = requirePositive(expertTimeout, "expert-timeout"); }

    public Duration getRunTimeout() { return runTimeout; }
    public void setRunTimeout(Duration runTimeout) { this.runTimeout = requirePositive(runTimeout, "run-timeout"); }

    public int getMaxParallel() { return maxParallel; }
    public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
