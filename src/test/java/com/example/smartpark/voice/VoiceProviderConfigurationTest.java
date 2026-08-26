package com.example.smartpark.voice;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionModel;
import com.example.smartpark.voice.port.StreamingAsrPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean conditioning contract: the port is created only from the real
 * DashScope transcription model when credentials exist; there is no mock
 * substitute. Session wiring (Task 6) hard-requires this bean, so a voice
 * deployment without credentials fails startup instead of silently degrading.
 */
class VoiceProviderConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VoiceProviderConfiguration.class);

    @Test
    void createsRealAsrPortWhenCredentialsAndModelExist() {
        runner
                .withPropertyValues("spring.ai.dashscope.api-key=sk-test")
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(StreamingAsrPort.class);
                    assertThat(context.getBean(StreamingAsrPort.class))
                            .isInstanceOf(com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingAsrAdapter.class);
                });
    }

    @Test
    void noPortWithoutApiKey() {
        runner
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .run(context -> assertThat(context).doesNotHaveBean(StreamingAsrPort.class));
    }

    @Test
    void blankApiKeyCountsAsMissingCredentials() {
        runner
                .withPropertyValues("spring.ai.dashscope.api-key=")
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .run(context -> assertThat(context).doesNotHaveBean(StreamingAsrPort.class));
    }

    @Test
    void noPortWhenVoiceFeatureDisabled() {
        runner
                .withPropertyValues(
                        "spring.ai.dashscope.api-key=sk-test",
                        "smartpark.voice.enabled=false")
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .run(context -> assertThat(context).doesNotHaveBean(StreamingAsrPort.class));
    }

    @Test
    void missingModelBeanFailsStartupInsteadOfSilentAbsence() {
        // Credentials present but the real transcription model is missing:
        // misconfiguration must fail loudly, never degrade silently.
        runner
                .withPropertyValues("spring.ai.dashscope.api-key=sk-test")
                .run(context -> assertThat(context).hasFailed());
    }
}
