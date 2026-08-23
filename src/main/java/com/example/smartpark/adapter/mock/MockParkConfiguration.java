package com.example.smartpark.adapter.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class MockParkConfiguration {

    @Bean
    MockParkDataStore mockParkDataStore() {
        return new MockParkDataStore();
    }

    @Bean
    MockAlertAdapter mockAlertAdapter(MockParkDataStore dataStore) {
        return new MockAlertAdapter(dataStore);
    }

    @Bean
    MockDeviceAdapter mockDeviceAdapter(MockParkDataStore dataStore) {
        return new MockDeviceAdapter(dataStore);
    }

    @Bean
    MockEnergyAdapter mockEnergyAdapter(MockParkDataStore dataStore) {
        return new MockEnergyAdapter(dataStore);
    }

    @Bean
    MockKnowledgeAdapter mockKnowledgeAdapter(MockParkDataStore dataStore) {
        return new MockKnowledgeAdapter(dataStore);
    }

    @Bean
    MockWorkOrderAdapter mockWorkOrderAdapter(MockParkDataStore dataStore) {
        return new MockWorkOrderAdapter(dataStore);
    }
}
