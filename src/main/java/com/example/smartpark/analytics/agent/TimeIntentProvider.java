package com.example.smartpark.analytics.agent;

import java.time.Instant;

public interface TimeIntentProvider {

    TimeIntentResult resolve(String question, Instant now);
}
