package com.example.smartpark.collaboration.expert;

import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean({EnergyQueryTool.class, ParkKnowledgeTool.class})
public class EnergyExpertConfiguration {
    @Bean(name = "energyExpertTools")
    ExpertToolSet energyExpertTools(EnergyQueryTool energy, ParkKnowledgeTool knowledge) {
        return ExpertToolSet.of(energy, knowledge);
    }
}
