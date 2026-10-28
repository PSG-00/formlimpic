package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The PostgreSQL reads for archived forms must give exactly what the in-memory ranking gave. */
class ArchivedResultsTests {
    @TempDir
    Path dir;

    @Test
    void archivedRankingMineAndMySubmissionsMatchTheInMemoryOnes() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:archive" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE memberships (id UUID PRIMARY KEY, form_id UUID NOT NULL, owner_id UUID NOT NULL, code CHAR(6) NOT NULL)");
        jdbc.execute("CREATE TABLE submissions (id UUID PRIMARY KEY, form_id UUID NOT NULL, ticket_id UUID NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, code CHAR(6) NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL, admission_sequence BIGINT NOT NULL, early BOOLEAN NOT NULL, birth_date TEXT, bubble TEXT)");
        ReceiptStoreTests.TestClock clock = new ReceiptStoreTests.TestClock();
        try (ReceiptStore s = new ReceiptStore(dir.resolve("journal"), clock)) {
            var f = s.create("archived", "c", clock.value.plusSeconds(60), clock.value.plusSeconds(120));
            List<String> users = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                users.add(s.registerUser("a" + i, "hash").id());
                s.ticket(f.id(), users.get(i));
            }
            Instant start = f.startsAt();
            clock.value = start;
            long[] admittedAfterStart = {300, 150, -5, 150, 20}; // out of arrival order, one early, one tie
            for (int i = 0; i < 5; i++) s.submit(f.id(), users.get(i), "참가자" + i, "000101", "010", "", start.plusMillis(admittedAfterStart[i]));
            clock.value = ReceiptStore.resultsAt(f);
            ReceiptStore.Ranking expected = s.ranking(f.id());
            ReceiptStore.SubmissionSummary expectedMine = s.mySubmissions(users.get(3)).get(0);

            // what DatabaseWriter projected
            for (ReceiptStore.Receipt r : expected.receipts()) {
                ReceiptStore.Ticket t = s.ticketById(r.ticketId()).orElseThrow();
                jdbc.update("INSERT INTO memberships VALUES (?, ?, ?, ?)", UUID.fromString(t.id()), UUID.fromString(t.formId()), UUID.fromString(t.owner()), t.code());
                jdbc.update("INSERT INTO submissions (id, form_id, ticket_id, name, phone, code, received_at, admission_sequence, early, birth_date, bubble) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.fromString(r.id()), UUID.fromString(r.formId()), UUID.fromString(r.ticketId()), r.name(), r.phone(), r.code(),
                        Timestamp.from(r.receivedAt()), r.sequence(), r.early(), r.birthDate(), r.bubble());
            }
            s.markFormNotified(f.id());
            s.archiveForm(f.id());

            ArchivedResults archive = new ArchivedResults(jdbc, s);
            assertEquals(expected.receipts(), archive.ranking(f.id()).receipts());
            assertEquals(expected.rankByTicket(), archive.ranking(f.id()).rankByTicket());
            assertEquals(expectedMine.receipt(), archive.mine(f.id(), users.get(3)));
            assertNull(archive.mine(f.id(), UUID.randomUUID().toString()));
            var mine = archive.mySubmissions(users.get(3));
            assertEquals(1, mine.size());
            assertEquals(expectedMine.rank(), mine.get(0).rank());
            assertEquals(expectedMine.receipt(), mine.get(0).receipt());
            assertEquals("archived", mine.get(0).form().title());
        }
    }
}
