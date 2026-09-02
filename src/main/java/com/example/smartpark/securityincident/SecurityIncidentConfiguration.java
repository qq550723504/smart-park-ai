package com.example.smartpark.securityincident;

import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({SecurityEventReader.class, AlertPort.class, SecurityIncidentHandoffPort.class})
public class SecurityIncidentConfiguration {

    @Bean
    SecurityIncidentStore securityIncidentStore() {
        return new SecurityIncidentStore(100);
    }

    @Bean
    SecurityIncidentService securityIncidentService(SecurityEventReader security, AlertPort alerts,
                                                    SecurityIncidentStore store,
                                                    SecurityIncidentHandoffPort handoffs) {
        return new SecurityIncidentService(security, alerts, store, handoffs, Clock.systemUTC());
    }
}
