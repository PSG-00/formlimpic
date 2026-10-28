package com.formlimpic;

import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.IOException;
import java.util.List;
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
    private static final int BATCH = 1000;
    private static final long MAX_FLUSH_MS = 5000;
    private Long cursor; // last projected journal sequence; loaded from projection_state
    private boolean initialized;
    private boolean outage;
    DatabaseWriter(ReceiptStore store, JdbcTemplate jdbc, PlatformTransactionManager manager) { this.store = store; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void flush() {
        try {
            if (!initialized) {
                jdbc.execute("CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, username TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, membership_code CHAR(6) UNIQUE, created_at TIMESTAMPTZ NOT NULL)");
                jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS membership_code CHAR(6) UNIQUE");
                jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS discord_webhook_url TEXT");
                jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role TEXT DEFAULT 'USER'");
                jdbc.execute("CREATE TABLE IF NOT EXISTS forms (id UUID PRIMARY KEY, owner_id UUID, title TEXT NOT NULL, content TEXT NOT NULL, starts_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL)");
                jdbc.execute("ALTER TABLE forms ADD COLUMN IF NOT EXISTS owner_id UUID");
                jdbc.execute("ALTER TABLE forms ADD COLUMN IF NOT EXISTS has_bubble BOOLEAN DEFAULT FALSE");
                jdbc.execute("ALTER TABLE forms ADD COLUMN IF NOT EXISTS admin_created BOOLEAN DEFAULT FALSE");
                jdbc.execute("ALTER TABLE forms ADD COLUMN IF NOT EXISTS has_delay BOOLEAN DEFAULT TRUE");
                jdbc.execute("CREATE TABLE IF NOT EXISTS memberships (id UUID PRIMARY KEY, form_id UUID NOT NULL REFERENCES forms(id), owner_id UUID NOT NULL, code CHAR(6) NOT NULL, UNIQUE(form_id, code), UNIQUE(form_id, owner_id))");
                jdbc.execute("CREATE TABLE IF NOT EXISTS submissions (id UUID PRIMARY KEY, form_id UUID NOT NULL REFERENCES forms(id), ticket_id UUID NOT NULL UNIQUE REFERENCES memberships(id), name TEXT NOT NULL, phone TEXT NOT NULL, code CHAR(6) NOT NULL, received_at TIMESTAMPTZ NOT NULL, admission_sequence BIGINT NOT NULL UNIQUE, early BOOLEAN NOT NULL)");
                jdbc.execute("ALTER TABLE submissions ADD COLUMN IF NOT EXISTS birth_date TEXT");
                jdbc.execute("ALTER TABLE submissions ADD COLUMN IF NOT EXISTS bubble TEXT");
                jdbc.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                jdbc.execute("CREATE TABLE IF NOT EXISTS projection_state (id INT PRIMARY KEY, sequence BIGINT NOT NULL)");
                jdbc.execute("CREATE TABLE IF NOT EXISTS projection_failures (sequence BIGINT PRIMARY KEY, type TEXT, error TEXT, failed_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                initialized = true;
            }
            if (cursor == null) cursor = loadCursor();
            // Catch up in batches (one transaction each) instead of 100 events per second
            long deadline = System.currentTimeMillis() + MAX_FLUSH_MS;
            while (System.currentTimeMillis() < deadline) {
                List<ReceiptStore.Event> batch = store.durableEventsAfter(cursor, BATCH);
                if (batch.isEmpty()) break;
                saveBatch(batch);
                cursor = batch.get(batch.size() - 1).sequence();
            }
            if (outage) log.info("PostgreSQL projection resumed; durable journal retained"); outage = false;
        } catch (RuntimeException | IOException e) { cursor = null; if (!outage) log.warn("PostgreSQL unavailable; acknowledged receipts remain in journal and will be retried: {}", e.getClass().getSimpleName()); outage = true; }
    }
    private long loadCursor() {
        List<Long> rows = jdbc.queryForList("SELECT sequence FROM projection_state WHERE id = 1", Long.class);
        long saved = rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
        long journal = store.durableSequence();
        if (saved > journal) {
            log.warn("Projection cursor {} is ahead of the journal ({} events): journal was restored or repaired; continuing from {}", saved, journal, journal);
            return journal;
        }
        return saved;
    }
    private void storeCursor(long sequence) {
        jdbc.update("INSERT INTO projection_state (id, sequence) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET sequence = EXCLUDED.sequence", sequence);
    }
    private void saveBatch(List<ReceiptStore.Event> batch) {
        long last = batch.get(batch.size() - 1).sequence();
        try {
            tx.executeWithoutResult(status -> { for (ReceiptStore.Event e : batch) save(e); storeCursor(last); });
        } catch (DataIntegrityViolationException bad) {
            // One event the DB rejects must not stop the projection: retry one by one and set rejected ones aside.
            // Connection errors are not DataIntegrityViolationException, so an outage still stops and retries later.
            for (ReceiptStore.Event e : batch) {
                try {
                    tx.executeWithoutResult(status -> { save(e); storeCursor(e.sequence()); });
                } catch (DataIntegrityViolationException rejected) {
                    log.warn("Projection skipped journal event {} ({}): {}", e.sequence(), e.values().getProperty("type"), rejected.getMostSpecificCause().getMessage());
                    tx.executeWithoutResult(status -> {
                        jdbc.update("INSERT INTO projection_failures (sequence, type, error) VALUES (?, ?, ?) ON CONFLICT (sequence) DO NOTHING",
                                e.sequence(), e.values().getProperty("type"), String.valueOf(rejected.getMostSpecificCause().getMessage()));
                        storeCursor(e.sequence());
                    });
                }
            }
        }
    }
    private void save(ReceiptStore.Event e) {
        Properties p = e.values();
        switch (p.getProperty("type")) {
            case "user" -> jdbc.update("INSERT INTO users (id, username, password_hash, membership_code, discord_webhook_url, role, created_at) VALUES (?::uuid, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET role = EXCLUDED.role", p.getProperty("id"), p.getProperty("username"), p.getProperty("hash"), p.getProperty("code"), p.getProperty("webhook", ""), p.getProperty("role", "USER"), time(p, "time"));
            case "user_webhook" -> jdbc.update("UPDATE users SET discord_webhook_url = ? WHERE id = ?::uuid", p.getProperty("webhook"), p.getProperty("userId"));
            case "user_password" -> jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?::uuid", p.getProperty("hash"), p.getProperty("userId"));
            case "form_notified" -> {}
            case "form" -> {
                String owner = p.getProperty("owner");
                Object ownerId = (owner != null && owner.matches("[0-9a-f-]{36}")) ? java.util.UUID.fromString(owner) : null;
                boolean hasBubble = Boolean.parseBoolean(p.getProperty("hasBubble", "false"));
                boolean adminCreated = Boolean.parseBoolean(p.getProperty("adminCreated", "false"));
                boolean hasDelay = Boolean.parseBoolean(p.getProperty("hasDelay", "true"));
                jdbc.update("INSERT INTO forms (id, owner_id, title, content, starts_at, expires_at, has_bubble, admin_created, has_delay) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET has_bubble = EXCLUDED.has_bubble, admin_created = EXCLUDED.admin_created, has_delay = EXCLUDED.has_delay", p.getProperty("id"), ownerId, p.getProperty("title"), p.getProperty("content"), time(p, "start"), time(p, "end"), hasBubble, adminCreated, hasDelay);
            }
            case "form_deleted" -> {
                String formId = p.getProperty("id");
                jdbc.update("DELETE FROM submissions WHERE form_id = ?::uuid", formId);
                jdbc.update("DELETE FROM memberships WHERE form_id = ?::uuid", formId);
                jdbc.update("DELETE FROM forms WHERE id = ?::uuid", formId);
            }
            case "setting" -> jdbc.update("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", p.getProperty("key"), p.getProperty("value"));
            case "ticket" -> jdbc.update("INSERT INTO memberships VALUES (?::uuid, ?::uuid, ?::uuid, ?) ON CONFLICT (id) DO NOTHING", p.getProperty("id"), p.getProperty("form"), p.getProperty("owner"), p.getProperty("code"));
            case "receipt" -> jdbc.update("INSERT INTO submissions (id, form_id, ticket_id, name, birth_date, phone, bubble, code, received_at, admission_sequence, early) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING", p.getProperty("id"), p.getProperty("form"), p.getProperty("ticket"), p.getProperty("name"), p.getProperty("birthDate", ""), p.getProperty("phone"), p.getProperty("bubble", ""), p.getProperty("code"), time(p, "time"), e.sequence(), Boolean.valueOf(p.getProperty("early")));
            default -> throw new IllegalStateException("Unknown event");
        }
    }
    private Timestamp time(Properties p, String key) { return Timestamp.from(Instant.parse(p.getProperty(key))); }
}
