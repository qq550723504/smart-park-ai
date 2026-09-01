package com.example.smartpark.operations;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsCapabilitiesServiceTest {

    @Test
    void normalizesModesAndDerivesRuntimeCapabilities() {
        OperationsCapabilitiesService service = new OperationsCapabilitiesService(
                "RAG", "DASHSCOPE", true, true, true, provider(new ExpertCollaborationService(
                        null, null, null, null, null, null, null, null)));

        OperationsCapabilitiesSnapshot snapshot = service.snapshot();

        assertThat(snapshot.knowledgeMode()).isEqualTo("rag");
        assertThat(snapshot.customerAnswerMode()).isEqualTo("dashscope");
        assertThat(snapshot.vectorStore()).isEqualTo("simple-vector-store");
        assertThat(snapshot.analyticsEnabled()).isTrue();
        assertThat(snapshot.collaborationEnabled()).isTrue();
        assertThat(snapshot.voiceEnabled()).isTrue();
    }

    @Test
    void fallsBackToMockForUnknownModes() {
        OperationsCapabilitiesService service = new OperationsCapabilitiesService(
                "unsupported", "unknown", false, false, false, provider(null));

        OperationsCapabilitiesSnapshot snapshot = service.snapshot();

        assertThat(snapshot.knowledgeMode()).isEqualTo("mock");
        assertThat(snapshot.customerAnswerMode()).isEqualTo("mock");
        assertThat(snapshot.vectorStore()).isEqualTo("none");
        assertThat(snapshot.collaborationEnabled()).isFalse();
        assertThat(snapshot.voiceEnabled()).isFalse();
    }

    @Test
    void hidesVoiceWhenLocalDemoTransportIsDisabled() {
        OperationsCapabilitiesService service = new OperationsCapabilitiesService(
                "mock", "mock", false, true, false, provider(null));

        assertThat(service.snapshot().voiceEnabled()).isFalse();
    }

    private static ObjectProvider<ExpertCollaborationService> provider(Object value) {
        return new ObjectProvider<>() {
            @Override public ExpertCollaborationService getIfAvailable() {
                return value == null ? null : (ExpertCollaborationService) value;
            }
            @Override public ExpertCollaborationService getIfUnique() { return getIfAvailable(); }
            @Override public ExpertCollaborationService getObject(Object... args) { return getIfAvailable(); }
            @Override public ExpertCollaborationService getObject() { return getIfAvailable(); }
        };
    }
}
