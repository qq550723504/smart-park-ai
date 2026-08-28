package com.example.smartpark.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceOnlineSmokeGateTest {

    @Test
    void onlineSmokeRequiresExplicitOptInFlagInAdditionToApiKey() {
        EnabledIf gate = VoiceOnlineSmokeTest.class.getAnnotation(EnabledIf.class);

        assertThat(gate).as("voice online smoke must require explicit opt-in").isNotNull();
        assertThat(gate.expression())
                .isEqualTo("#{systemProperties['run.dashscope.smoke'] == 'true'}");
        assertThat(gate.loadContext()).isFalse();
    }
}
