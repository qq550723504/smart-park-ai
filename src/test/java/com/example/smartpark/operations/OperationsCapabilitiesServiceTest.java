package com.example.smartpark.operations;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OperationsCapabilitiesServiceTest {

    @Test
    void normalizesModesAndDerivesRuntimeCapabilities() {
        OperationsCapabilitiesService service = new OperationsCapabilitiesService(
                "RAG", "DASHSCOPE", true, true, true, provider(new ExpertCollaborationService(
                        null, null, null, null, null, null, null, null)), provider(null));

        OperationsCapabilitiesSnapshot snapshot = service.snapshot();

        assertThat(snapshot.knowledgeMode()).isEqualTo("rag");
        assertThat(snapshot.customerAnswerMode()).isEqualTo("dashscope");
        assertThat(snapshot.vectorStore()).isEqualTo("simple-vector-store");
        assertThat(snapshot.analyticsEnabled()).isTrue();
        assertThat(snapshot.collaborationEnabled()).isTrue();
        assertThat(snapshot.voiceEnabled()).isTrue();
        assertThat(snapshot.securityIncidentEnabled()).isFalse();
    }

    @Test
    void exposesSecurityIncidentCapabilityOnlyWhenItsRuntimeBeanIsAvailable() {
        OperationsCapabilitiesService service = new OperationsCapabilitiesService(
                "mock", "mock", false, false, false, provider(null), provider(mock(SecurityIncidentService.class)));

        assertThat(service.snapshot().securityIncidentEnabled()).isTrue();
    }

    @Test
    void fallsBackToMockForUnknownModes() {
        OperationsCapabilitiesService service = new OperationsCapabilitiesService(
                "unsupported", "unknown", false, false, false, provider(null), provider(null));

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
                "mock", "mock", false, true, false, provider(null), provider(null));

        assertThat(service.snapshot().voiceEnabled()).isFalse();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return getIfAvailable(); }
            @Override public T getObject(Object... args) { return getIfAvailable(); }
            @Override public T getObject() { return getIfAvailable(); }
        };
    }
}
