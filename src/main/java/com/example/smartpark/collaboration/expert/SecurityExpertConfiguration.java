package com.example.smartpark.collaboration.expert;

import com.example.smartpark.tool.security.SecurityQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityExpertConfiguration {
    @Bean(name = "securityExpertTools")
    ExpertToolSet securityExpertTools(SecurityQueryTool security, ParkKnowledgeTool knowledge) {
        return ExpertToolSet.of(security, knowledge);
    }
}
