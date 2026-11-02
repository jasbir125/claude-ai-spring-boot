package com.example.order.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inbox of consumed events ({@code processed_event}); joins the caller's JPA transaction. */
@Repository
@RequiredArgsConstructor
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    /**
     * Records that {@code handler} processed {@code eventId}. Must run in the same transaction as the handler's
     * changes: if they roll back, so does this row, and a redelivery is processed again.
     *
     * @return {@code true} the first time, {@code false} for a duplicate delivery
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String handler) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_event (event_id, handler) VALUES (:eventId, :handler)
                        ON CONFLICT DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("handler", handler)
                .update();
        return inserted == 1;
    }

    public long count(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }

    @Transactional
    public int deleteProcessedBefore(Instant before) {
        return jdbc.sql("DELETE FROM processed_event WHERE processed_at < :before")
                .param("before", Timestamp.from(before))
                .update();
    }
}
