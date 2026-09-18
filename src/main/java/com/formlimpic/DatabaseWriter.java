package com.formlimpic;

import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Properties;
import java.sql.Timestamp;
import java.time.Instant;

/** Journal is authoritative; this projection can be rebuilt and retried idempotently. */
@Component
@EnableScheduling
public class DatabaseWriter {
    private static final Logger log = LoggerFactory.getLogger(DatabaseWriter.class);
    private final ReceiptStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private long cursor;
    private boolean initialized;
    private boolean outage;
    DatabaseWriter(ReceiptStore store, JdbcTemplate jdbc, PlatformTransactionManager manager) { this.store = store; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void flush() {
        try {
            if (!initialized) {
                jdbc.execute("CREATE TABLE IF NOT EXISTS forms (id UUID PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL, starts_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL)");
                jdbc.execute("CREATE TABLE IF NOT EXISTS memberships (id UUID PRIMARY KEY, form_id UUID NOT NULL REFERENCES forms(id), owner_id UUID NOT NULL, code CHAR(6) NOT NULL, UNIQUE(form_id, code), UNIQUE(form_id, owner_id))");
                jdbc.execute("CREATE TABLE IF NOT EXISTS submissions (id UUID PRIMARY KEY, form_id UUID NOT NULL REFERENCES forms(id), ticket_id UUID NOT NULL UNIQUE REFERENCES memberships(id), name TEXT NOT NULL, phone TEXT NOT NULL, code CHAR(6) NOT NULL, received_at TIMESTAMPTZ NOT NULL, admission_sequence BIGINT NOT NULL UNIQUE, early BOOLEAN NOT NULL)");
                initialized = true;
            }
            for (ReceiptStore.Event event : store.eventsAfter(cursor)) {
                tx.executeWithoutResult(status -> save(event)); cursor = event.sequence();
            }
            if (outage) log.info("PostgreSQL projection resumed; durable journal retained"); outage = false;
        } catch (RuntimeException e) { if (!outage) log.warn("PostgreSQL unavailable; acknowledged receipts remain in journal and will be retried: {}", e.getClass().getSimpleName()); outage = true; }
    }
    private void save(ReceiptStore.Event e) {
        Properties p = e.values();
        switch (p.getProperty("type")) {
            case "form" -> jdbc.update("INSERT INTO forms VALUES (?::uuid, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING", p.getProperty("id"), p.getProperty("title"), p.getProperty("content"), time(p, "start"), time(p, "end"));
            case "ticket" -> jdbc.update("INSERT INTO memberships VALUES (?::uuid, ?::uuid, ?::uuid, ?) ON CONFLICT (id) DO NOTHING", p.getProperty("id"), p.getProperty("form"), p.getProperty("owner"), p.getProperty("code"));
            case "receipt" -> jdbc.update("INSERT INTO submissions VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING", p.getProperty("id"), p.getProperty("form"), p.getProperty("ticket"), p.getProperty("name"), p.getProperty("phone"), p.getProperty("code"), time(p, "time"), e.sequence(), Boolean.valueOf(p.getProperty("early")));
            default -> throw new IllegalStateException("Unknown event");
        }
    }
    private Timestamp time(Properties p, String key) { return Timestamp.from(Instant.parse(p.getProperty(key))); }
}
