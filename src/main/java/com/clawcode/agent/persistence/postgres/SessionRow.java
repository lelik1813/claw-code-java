package com.clawcode.agent.persistence.postgres;

import com.clawcode.agent.core.session.SessionRecord;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("sessions")
public record SessionRow(
    @Id UUID id,
    Instant createdAt
) {

    public static SessionRow from(SessionRecord record) {
        return new SessionRow(UUID.fromString(record.sessionId()), record.createdAt());
    }

    public SessionRecord toRecord() {
        return new SessionRecord(id.toString(), createdAt);
    }
}
