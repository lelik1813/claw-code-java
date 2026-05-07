package com.clawcode.agent.config;

import com.clawcode.agent.core.session.InMemorySessionRegistry;
import com.clawcode.agent.core.session.PostgresSessionRegistry;
import com.clawcode.agent.core.session.SessionRegistry;
import com.clawcode.agent.persistence.InMemoryTranscriptStore;
import com.clawcode.agent.persistence.PostgresTranscriptStore;
import com.clawcode.agent.persistence.TranscriptStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class PersistenceConfig {

    @Configuration
    @ConditionalOnProperty(name = "app.persistence.backend", havingValue = "r2dbc")
    static class R2dbcConfig {

        @Bean
        TransactionalOperator transactionalOperator(ReactiveTransactionManager txManager) {
            return TransactionalOperator.create(txManager);
        }

        @Bean
        SessionRegistry sessionRegistry(R2dbcEntityTemplate template) {
            return new PostgresSessionRegistry(template);
        }

        @Bean
        TranscriptStore transcriptStore(R2dbcEntityTemplate template, TransactionalOperator txOp,
                                         com.clawcode.agent.forensics.AuditTrail auditTrail,
                                         com.clawcode.agent.forensics.ObservabilityMetrics metrics) {
            return new PostgresTranscriptStore(template, txOp, auditTrail, metrics);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.persistence.backend", havingValue = "in-memory")
    static class InMemoryConfig {

        @Bean
        SessionRegistry sessionRegistry() {
            return new InMemorySessionRegistry();
        }

        @Bean
        TranscriptStore transcriptStore(com.clawcode.agent.forensics.AuditTrail auditTrail) {
            return new InMemoryTranscriptStore(auditTrail);
        }
    }
}
