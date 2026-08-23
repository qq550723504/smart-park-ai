package com.example.smartpark.adapter.mock;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.dashscope.enabled=true",
        "spring.ai.dashscope.api-key=test-key"
})
class MockParkConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void registersCapabilityPortsWithoutTheAggregateMock() {
        assertThat(applicationContext.getBeansOfType(MockParkConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AlertPort.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(DevicePort.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(EnergyPort.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(KnowledgePort.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(WorkOrderPort.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(SecurityPort.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MockAlertAdapter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MockDeviceAdapter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MockEnergyAdapter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MockSecurityAdapter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MockKnowledgeAdapter.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(MockWorkOrderAdapter.class)).hasSize(1);

        MockParkDataStore dataStore = applicationContext.getBean(MockParkDataStore.class);
        assertThat(readDataStoreField(applicationContext.getBean(MockAlertAdapter.class))).isSameAs(dataStore);
        assertThat(readDataStoreField(applicationContext.getBean(MockDeviceAdapter.class))).isSameAs(dataStore);
        assertThat(readDataStoreField(applicationContext.getBean(MockEnergyAdapter.class))).isSameAs(dataStore);
        assertThat(readDataStoreField(applicationContext.getBean(MockSecurityAdapter.class))).isSameAs(dataStore);
        assertThat(readDataStoreField(applicationContext.getBean(MockKnowledgeAdapter.class))).isSameAs(dataStore);
        assertThat(readDataStoreField(applicationContext.getBean(MockWorkOrderAdapter.class))).isSameAs(dataStore);

        assertThat(applicationContext.getBeansOfType(Object.class).entrySet())
                .noneMatch(entry -> entry.getKey().equals("mockParkSystem")
                        || entry.getValue().getClass().getSimpleName().equals("MockParkSystem"));
    }

    private static Object readDataStoreField(Object adapter) {
        try {
            Field dataStore = adapter.getClass().getDeclaredField("dataStore");
            assertThat(dataStore.getType()).isEqualTo(MockParkDataStore.class);
            assertThat(Modifier.isPrivate(dataStore.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(dataStore.getModifiers())).isTrue();
            assertThat(dataStore.trySetAccessible()).isTrue();
            return dataStore.get(adapter);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Adapter must expose its existing private dataStore field for this test", exception);
        }
    }
}
