package com.clawcode.agent.core.session;

import com.clawcode.agent.persistence.postgres.SessionRow;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class PostgresSessionRegistry implements SessionRegistry {

    private final R2dbcEntityTemplate template;

    public PostgresSessionRegistry(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public Mono<SessionRecord> register(String sessionId) {
        SessionRow row = new SessionRow(UUID.fromString(sessionId), Instant.now());
        return template.insert(SessionRow.class)
            .using(row)
            .map(SessionRow::toRecord);
    }

    @Override
    public Mono<SessionRecord> find(String sessionId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return Mono.empty();
        }
        return template.select(SessionRow.class)
            .matching(Query.query(Criteria.where("id").is(uuid)))
            .one()
            .map(SessionRow::toRecord);
    }

    @Override
    public Flux<SessionRecord> listAll() {
        return template.select(SessionRow.class)
            .all()
            .map(SessionRow::toRecord);
    }
}
