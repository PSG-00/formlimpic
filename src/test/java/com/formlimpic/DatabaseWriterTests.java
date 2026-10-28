package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DatabaseWriterTests {
    @TempDir Path dir;

    private static PlatformTransactionManager manager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return manager;
    }

    @Test void failedWriteRetriesSameEventThenMovesOn() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            store.create("test", "test", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            PlatformTransactionManager manager = manager();
            when(jdbc.update(startsWith("INSERT INTO forms"), any(Object[].class))).thenThrow(new RuntimeException("DB offline")).thenReturn(1);
            DatabaseWriter writer = new DatabaseWriter(store, jdbc, manager);
            writer.flush(); writer.flush(); writer.flush();
            verify(jdbc, times(2)).update(startsWith("INSERT INTO forms"), any(Object[].class));
            verify(jdbc).update(startsWith("INSERT INTO projection_state"), eq(1L));
            verify(manager).rollback(any()); verify(manager).commit(any());
        }
    }

    @Test void rejectedEventIsSetAsideAndTheRestIsProjected() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            store.create("rejected", "test", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
            store.create("fine", "test", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // batch attempt fails on event 1, one-by-one retry fails on event 1 again, event 2 succeeds
            when(jdbc.update(startsWith("INSERT INTO forms"), any(Object[].class)))
                    .thenThrow(new DuplicateKeyException("dup")).thenThrow(new DuplicateKeyException("dup")).thenReturn(1);
            DatabaseWriter writer = new DatabaseWriter(store, jdbc, manager());
            writer.flush();
            verify(jdbc).update(startsWith("INSERT INTO projection_failures"), eq(1L), eq("form"), anyString());
            verify(jdbc).update(startsWith("INSERT INTO projection_state"), eq(2L));
            writer.flush();
            verify(jdbc, times(3)).update(startsWith("INSERT INTO forms"), any(Object[].class)); // nothing left to retry
        }
    }

    @Test void resumesFromSavedCursorInsteadOfReplayingHistory() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            store.create("projected before restart", "test", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
            store.create("new", "test", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(startsWith("SELECT sequence FROM projection_state"), eq(Long.class))).thenReturn(List.of(1L));
            new DatabaseWriter(store, jdbc, manager()).flush();
            verify(jdbc, times(1)).update(startsWith("INSERT INTO forms"), any(Object[].class));
            verify(jdbc).update(startsWith("INSERT INTO projection_state"), eq(2L));
        }
    }
}
