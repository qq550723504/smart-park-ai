package com.example.smartpark.web;

import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.voice.DeadlineScheduler;
import com.example.smartpark.voice.VoiceAnswerAgent;
import com.example.smartpark.voice.VoiceDeadlines;
import com.example.smartpark.voice.VoiceSessionService;
import com.example.smartpark.voice.VoiceSessionStore;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;

/**
 * Opt-in voice session wiring via smartpark.voice.enabled=true.
 *
 * Every bean here hard-requires the real provider ports, so enabling voice
 * without DashScope credentials fails application startup loudly instead of
 * silently degrading; CI contexts that never enable voice load none of this.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(VoiceProperties.class)
@ConditionalOnProperty(prefix = "smartpark.voice", name = "enabled", havingValue = "true")
public class VoiceSessionConfiguration {

    @Bean
    VoiceSessionStore voiceSessionStore(VoiceDeadlines deadlines) {
        return new VoiceSessionStore(deadlines);
    }

    @Bean
    VoiceDeadlines voiceDeadlines(VoiceProperties properties) {
        return properties.getBudgets().toDeadlines();
    }

    @Bean
    public ThreadPoolTaskScheduler voiceDeadlineTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("voice-deadline-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean
    DeadlineScheduler deadlineScheduler(
            @Qualifier("voiceDeadlineTaskScheduler") ThreadPoolTaskScheduler taskScheduler) {
        return (task, delay) -> {
            var scheduled = taskScheduler.schedule(task, java.time.Instant.now().plus(delay));
            return () -> scheduled.cancel(false);
        };
    }

    // Missing ASR/TTS ports here are intentional: bean resolution failure = loud startup error.
    @Bean
    VoiceSessionService voiceSessionService(
            VoiceSessionStore store,
            StreamingAsrPort asrPort,
            StreamingTtsPort ttsPort,
            VoiceAnswerAgent answerAgent,
            ExecutionEventPublisher eventPublisher,
            VoiceDeadlines deadlines,
            DeadlineScheduler deadlineScheduler,
            org.springframework.core.task.AsyncTaskExecutor voiceAgentExecutor) {
        return new VoiceSessionService(store, asrPort, ttsPort, answerAgent,
                eventPublisher, deadlines, deadlineScheduler, voiceAgentExecutor::execute);
    }

    @Bean(name = "voiceAgentExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler voiceAgentPool() {
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(4);
        pool.setThreadNamePrefix("voice-agent-");
        pool.setDaemon(true);
        return pool;
    }

    @Bean
    VoiceWebSocketHandler voiceWebSocketHandler(
            VoiceSessionService service,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            VoiceProperties properties) {
        return new VoiceWebSocketHandler(service, objectMapper, properties.getMaxBinaryFrameBytes());
    }

    @Bean
    public WebSocketConfigurer voiceWebSocketConfigurer(
            VoiceWebSocketHandler handler, VoiceProperties properties) {
        return registry -> register(registry, handler, properties);
    }

    private void register(@NonNull org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry registry,
                          @NonNull VoiceWebSocketHandler handler,
                          @NonNull VoiceProperties properties) {
        var registration = registry.addHandler(handler, "/ws/voice/sessions/**");
        if (!properties.getAllowedOrigins().isEmpty()) {
            // Empty list = Spring's default same-origin policy (fail-closed).
            registration.setAllowedOrigins(properties.getAllowedOrigins().toArray(new String[0]));
        }
    }
}
