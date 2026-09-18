package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.nio.file.Path;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DatabaseWriterTests {
    @TempDir Path dir;
    @Test void failedWriteRetriesSameEventThenMovesOn() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            store.create("test", "test", Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("DB offline")).thenReturn(1);
            DatabaseWriter writer = new DatabaseWriter(store, jdbc, manager);
            writer.flush(); writer.flush(); writer.flush();
            verify(jdbc, times(2)).update(anyString(), any(Object[].class));
            verify(manager).rollback(any()); verify(manager).commit(any());
        }
    }
}
