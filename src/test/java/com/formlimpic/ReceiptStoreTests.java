package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptStoreTests {
    @TempDir Path dir;
    static class TestClock extends Clock {
        Instant value = Instant.parse("2026-09-18T07:55:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return value; }
    }
    @Test void boundaryRankingIdempotencyAndRestart() throws Exception {
        TestClock clock = new TestClock(); Path path = dir.resolve("journal"); String id; String receiptId;
        try (ReceiptStore s = new ReceiptStore(path, clock)) {
            var f = s.create("test", "content", Instant.parse("2026-09-18T08:00:00Z"), Instant.parse("2026-09-18T08:01:00Z")); id = f.id();
            s.ticket(id, "early"); s.ticket(id, "normal"); s.ticket(id, "late");
            clock.value = f.startsAt().minusNanos(1);
            var early = s.submit(id, "early", "fake", "TEST"); assertTrue(early.early());
            clock.value = f.startsAt();
            var normal = s.submit(id, "normal", "fake2", "TEST2"); receiptId = normal.id(); assertFalse(normal.early());
            assertEquals(normal, s.submit(id, "normal", "changed", "changed"));
            assertThrows(IllegalArgumentException.class, () -> s.results(id));
            clock.value = f.expiresAt();
            assertThrows(IllegalArgumentException.class, () -> s.submit(id, "late", "fake", "TEST"));
            assertEquals(List.of(normal, early), s.results(id));
        }
        try (ReceiptStore restored = new ReceiptStore(path, clock)) {
            assertEquals(receiptId, restored.mine(id, "normal").id());
            assertEquals(2, restored.results(id).size());
            assertEquals(receiptId, restored.submit(id, "normal", "x", "y").id());
        }
    }
    @Test void concurrentCodesAndReceiptsAreUnique() throws Exception {
        TestClock clock = new TestClock();
        try (ReceiptStore s = new ReceiptStore(dir.resolve("journal"), clock)) {
            var f = s.create("test", "test", clock.value.plusSeconds(1), clock.value.plusSeconds(100));
            ExecutorService pool = Executors.newFixedThreadPool(12);
            try {
                List<Callable<ReceiptStore.Receipt>> tasks = new ArrayList<>();
                for (int i=0;i<80;i++) { String owner = "owner"+i; tasks.add(() -> { s.ticket(f.id(), owner); return s.submit(f.id(), owner, "fake", "TEST"); }); }
                var results = pool.invokeAll(tasks); Set<String> codes = new HashSet<>(); Set<Long> sequences = new HashSet<>();
                for (var result : results) { var r = result.get(); assertTrue(r.code().matches("[A-Z]{6}")); codes.add(r.code()); sequences.add(r.sequence()); }
                assertEquals(80, codes.size()); assertEquals(80, sequences.size());
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void incompleteTailRecoveredButCorruptionFailsClosed() throws Exception {
        TestClock clock = new TestClock(); Path p = dir.resolve("journal");
        try (ReceiptStore s = new ReceiptStore(p, clock)) { s.create("test", "test", clock.value.plusSeconds(1), clock.value.plusSeconds(100)); }
        long size = Files.size(p); Files.write(p, new byte[]{1,2,3}, StandardOpenOption.APPEND);
        try (ReceiptStore s = new ReceiptStore(p, clock)) { assertEquals(1, s.forms().size()); assertEquals(size, Files.size(p)); }
        byte[] bytes = Files.readAllBytes(p); bytes[bytes.length-1] ^= 1; Files.write(p, bytes);
        assertThrows(java.io.IOException.class, () -> new ReceiptStore(p, clock));
    }
    @Test void openingBoundaryAndOneMembershipPerOwner() throws Exception {
        TestClock clock = new TestClock();
        try (ReceiptStore s = new ReceiptStore(dir.resolve("journal"), clock)) {
            var f = s.create("test", "test", clock.value.plusSeconds(601), clock.value.plusSeconds(900));
            assertThrows(IllegalArgumentException.class, () -> s.ticket(f.id(), "owner"));
            clock.value = clock.value.plusSeconds(1);
            assertEquals(s.ticket(f.id(), "owner"), s.ticket(f.id(), "owner"));
        }
    }
}
