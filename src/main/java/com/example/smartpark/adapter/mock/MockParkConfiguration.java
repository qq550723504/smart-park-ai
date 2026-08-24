package com.example.smartpark.adapter.mock;

import com.example.smartpark.demo.DemoFaultInjector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MockParkConfiguration {

    @Bean
    DemoFaultInjector demoFaultInjector() {
        return new DemoFaultInjector();
    }

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
    MockSecurityAdapter mockSecurityAdapter(MockParkDataStore dataStore) {
        return new MockSecurityAdapter(dataStore);
    }

    @Bean
    @ConditionalOnProperty(name = "smartpark.knowledge.mode", havingValue = "mock", matchIfMissing = true)
    MockKnowledgeAdapter mockKnowledgeAdapter(MockParkDataStore dataStore, DemoFaultInjector faultInjector) {
        return new MockKnowledgeAdapter(dataStore, faultInjector);
    }

    @Bean
    MockWorkOrderAdapter mockWorkOrderAdapter(MockParkDataStore dataStore) {
        return new MockWorkOrderAdapter(dataStore);
    }

    @Bean
    @ConditionalOnProperty(name = "smartpark.customer-service.answer-mode", havingValue = "mock", matchIfMissing = true)
    MockCustomerAnswerAdapter mockCustomerAnswerAdapter() {
        return new MockCustomerAnswerAdapter();
    }
}
