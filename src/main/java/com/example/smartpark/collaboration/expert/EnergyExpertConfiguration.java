package com.example.smartpark.collaboration.expert;

import com.example.smartpark.tool.energy.EnergyQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnergyExpertConfiguration {
    @Bean(name = "energyExpertTools")
    ExpertToolSet energyExpertTools(EnergyQueryTool energy, ParkKnowledgeTool knowledge) {
        return ExpertToolSet.of(energy, knowledge);
    }
}
