package com.example.smartpark.collaboration.expert;

import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Stream;

@Configuration
public class DeviceExpertConfiguration {
    @Bean(name = "deviceExpertTools")
    ExpertToolSet deviceExpertTools(DeviceQueryTool device, AlertQueryTool alert,
                                     WorkOrderTool workOrder, ParkKnowledgeTool knowledge) {
        ToolCallback[] callbacks = Stream.concat(
                        java.util.Arrays.stream(ToolCallbacks.from(device, alert, knowledge)),
                        java.util.Arrays.stream(workOrder.diagnosisCallbacks()))
                .toArray(ToolCallback[]::new);
        return new ExpertToolSet(List.of(device, alert, workOrder, knowledge), callbacks);
    }
}
