package com.example.smartpark.showcase;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "smartpark.showcase")
public class ShowcaseProperties {

    private Duration verificationTtl = Duration.ofMinutes(15);

    public Duration getVerificationTtl() {
        return verificationTtl;
    }

    public void setVerificationTtl(Duration verificationTtl) {
        this.verificationTtl = verificationTtl;
    }

    @PostConstruct
    void validate() {
        if (verificationTtl == null || verificationTtl.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException(
                    "smartpark.showcase.verification-ttl must be at least PT1M");
        }
    }
}
