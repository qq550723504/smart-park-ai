package com.example.smartpark;

import com.example.smartpark.collaboration.ExpertCollaborationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * The legacy smart-park runtime is in-memory; the analytics capability owns its
 * own DataSource bean (gated by smartpark.analytics.enabled), so the generic
 * JDBC/Flyway auto-configurations are excluded here to avoid requiring global
 * datasource properties.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(ExpertCollaborationProperties.class)
public class SmartParkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartParkApplication.class, args);
    }
}
