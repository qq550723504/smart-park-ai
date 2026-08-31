package com.example.smartpark.voice;

import com.alibaba.cloud.ai.dashscope.audio.transcription.DashScopeAudioTranscriptionModel;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionProperties;
import com.example.smartpark.voice.port.StreamingAsrPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean conditioning contract: the port is created only from the real
 * DashScope transcription model when credentials exist; there is no mock
 * substitute. Session wiring (Task 6) hard-requires this bean, so a voice
 * deployment without credentials fails startup instead of silently degrading.
 */
class VoiceProviderConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudioPropertiesConfiguration.class,
                    VoiceProviderConfiguration.class);

    @Test
    void createsRealAsrPortWhenCredentialsAndModelExist() {
        runner
                .withPropertyValues(validVoiceProperties())
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .withBean(com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel.class,
                        () -> Mockito.mock(com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(StreamingAsrPort.class);
                    assertThat(context.getBean(StreamingAsrPort.class))
                            .isInstanceOf(com.example.smartpark.voice.adapter.dashscope.DashScopeStreamingAsrAdapter.class);
                });
    }

    @Test
    void noPortWithoutApiKey() {
        runner
                .withPropertyValues("smartpark.voice.enabled=true")
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .run(context -> assertThat(context).doesNotHaveBean(StreamingAsrPort.class));
    }

    @Test
    void blankApiKeyCountsAsMissingCredentials() {
        runner
                .withPropertyValues(
                        "smartpark.voice.enabled=true",
                        "spring.ai.dashscope.api-key=")
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
                .withPropertyValues(validVoiceProperties())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void noPortWhenVoiceFeatureIsNotEnabled() {
        runner
                .withPropertyValues("spring.ai.dashscope.api-key=sk-test")
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .run(context -> assertThat(context).doesNotHaveBean(StreamingAsrPort.class));
    }

    @ParameterizedTest(name = "rejects {0}={1}")
    @MethodSource("invalidAudioOptions")
    void failsFastWhenEnabledVoiceUsesBlankOrUnsupportedAudioOptions(
            String property, String value) {
        runner
                .withPropertyValues(
                        "smartpark.voice.enabled=true",
                        "spring.ai.dashscope.api-key=sk-test",
                        "spring.ai.dashscope.audio.transcription.options.model=paraformer-realtime-v2",
                        "spring.ai.dashscope.audio.speech.options.model=cosyvoice-v2",
                        "spring.ai.dashscope.audio.speech.options.voice=longxiaochun_v2",
                        property + "=" + value)
                .withBean(DashScopeAudioTranscriptionModel.class,
                        () -> Mockito.mock(DashScopeAudioTranscriptionModel.class))
                .withBean(com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel.class,
                        () -> Mockito.mock(com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining(property);
                });
    }

    private static String[] validVoiceProperties() {
        return new String[] {
                "smartpark.voice.enabled=true",
                "spring.ai.dashscope.api-key=sk-test",
                "spring.ai.dashscope.audio.transcription.options.model=paraformer-realtime-v2",
                "spring.ai.dashscope.audio.speech.options.model=cosyvoice-v2",
                "spring.ai.dashscope.audio.speech.options.voice=longxiaochun_v2"
        };
    }

    private static Stream<Arguments> invalidAudioOptions() {
        return Stream.of(
                Arguments.of("spring.ai.dashscope.audio.transcription.options.model", ""),
                Arguments.of("spring.ai.dashscope.audio.transcription.options.model", "unsupported-asr"),
                Arguments.of("spring.ai.dashscope.audio.speech.options.model", ""),
                Arguments.of("spring.ai.dashscope.audio.speech.options.model", "unsupported-tts"),
                Arguments.of("spring.ai.dashscope.audio.speech.options.voice", ""),
                Arguments.of("spring.ai.dashscope.audio.speech.options.voice", "unsupported-voice"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            DashScopeAudioTranscriptionProperties.class,
            DashScopeAudioSpeechProperties.class
    })
    static class AudioPropertiesConfiguration {
    }
}
