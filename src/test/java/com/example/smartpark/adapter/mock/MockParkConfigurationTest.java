package com.example.smartpark.adapter.mock;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

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
        assertThat(applicationContext.getBeansOfType(Object.class).keySet())
                .noneMatch(name -> name.equals("mockParkSystem"));
    }
}
