package com.example.smartpark.adapter.mcp;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "smartpark.mcp.enabled", havingValue = "true")
public class SmartParkMcpConfiguration {
    @Bean
    SmartParkMcpTools smartParkMcpTools(AlertPort alertPort, EnergyPort energyPort, KnowledgePort knowledgePort) {
        return new SmartParkMcpTools(alertPort, energyPort, knowledgePort);
    }

    @Bean
    ToolCallbackProvider smartParkMcpToolCallbackProvider(SmartParkMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
