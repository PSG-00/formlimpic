package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookServiceTests {
    @TempDir
    Path dir;

    record Call(String url, long at) {}

    /** Replays the given responses in order, then answers 204. */
    static class FakeDiscord implements DiscordWebhookService.Transport {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final Deque<DiscordWebhookService.Response> script = new ArrayDeque<>();
        public synchronized DiscordWebhookService.Response post(String url, String body) {
            calls.add(new Call(url, System.currentTimeMillis()));
            DiscordWebhookService.Response r = script.poll();
            return r != null ? r : new DiscordWebhookService.Response(204, Map.of(), "");
        }
    }

    private static void awaitDrained(DiscordWebhookService s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (s.pending() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(0, s.pending());
    }

    @Test
    void rateLimitedDeliveryWaitsForRetryAfterAndIsSentAgain() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            FakeDiscord discord = new FakeDiscord();
            discord.script.add(new DiscordWebhookService.Response(429, Map.of("Retry-After", "0.3"), "{\"retry_after\": 0.3, \"global\": false}"));
            DiscordWebhookService service = new DiscordWebhookService(store, discord);
            service.sendTestNotification("https://discord.com/api/webhooks/1/a", "u");
            awaitDrained(service);
            assertEquals(2, discord.calls.size());
            assertTrue(discord.calls.get(1).at() - discord.calls.get(0).at() >= 300);
            service.shutdown();
        }
    }

    @Test
    void exhaustedWebhookBucketHoldsOnlyThatWebhook() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            FakeDiscord discord = new FakeDiscord();
            discord.script.add(new DiscordWebhookService.Response(204, Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset-after", "0.5"), ""));
            DiscordWebhookService service = new DiscordWebhookService(store, discord);
            service.sendTestNotification("https://discord.com/api/webhooks/1/a", "u");
            service.sendTestNotification("https://discord.com/api/webhooks/1/a", "u");
            service.sendTestNotification("https://discord.com/api/webhooks/2/b", "u");
            awaitDrained(service);
            assertEquals(3, discord.calls.size());
            assertEquals("https://discord.com/api/webhooks/2/b", discord.calls.get(1).url(), "other webhook isn't stuck behind the exhausted one");
            assertTrue(discord.calls.get(2).at() - discord.calls.get(0).at() >= 500);
            for (int i = 1; i < discord.calls.size(); i++) {
                assertTrue(discord.calls.get(i).at() - discord.calls.get(i - 1).at() >= DiscordWebhookService.MIN_INTERVAL_MS, "sends are paced");
            }
            service.shutdown();
        }
    }

    @Test
    void rejectedWebhookIsNotRetried() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            FakeDiscord discord = new FakeDiscord();
            discord.script.add(new DiscordWebhookService.Response(404, Map.of(), "{\"message\": \"Unknown Webhook\"}"));
            DiscordWebhookService service = new DiscordWebhookService(store, discord);
            service.sendTestNotification("https://discord.com/api/webhooks/1/a", "u");
            awaitDrained(service);
            assertEquals(1, discord.calls.size());
            service.shutdown();
        }
    }

    @Test
    void serverErrorIsRetriedWithBackoff() throws Exception {
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), Clock.systemUTC())) {
            FakeDiscord discord = new FakeDiscord();
            discord.script.add(new DiscordWebhookService.Response(502, Map.of(), ""));
            DiscordWebhookService service = new DiscordWebhookService(store, discord);
            service.sendTestNotification("https://discord.com/api/webhooks/1/a", "u");
            awaitDrained(service);
            assertEquals(2, discord.calls.size());
            assertTrue(discord.calls.get(1).at() - discord.calls.get(0).at() >= 1000);
            service.shutdown();
        }
    }
}
