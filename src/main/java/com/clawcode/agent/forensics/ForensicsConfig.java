package com.clawcode.agent.forensics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ForensicsConfig {

    @Bean
    AuditTrail auditTrail() {
        return new Slf4jAuditTrail();
    }
}
