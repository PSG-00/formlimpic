package com.formlimpic;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@EnableScheduling
public class DiscordWebhookService {
    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookService.class);
    // Discord allows about 50 requests/s per IP and only a few per webhook. One sender thread paced below that,
    // following X-RateLimit-* and 429 Retry-After, so a 5000-person result doesn't become 5000 parallel 429s
    static final int MAX_PENDING = 20_000;
    static final int MAX_ATTEMPTS = 5;
    static final long MIN_INTERVAL_MS = 40;
    static final long MAX_RETRY_AFTER_MS = 60_000;
    private static final Pattern RETRY_AFTER_BODY = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private final ReceiptStore store;
    private final Transport transport;
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discord-sender");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pending = new AtomicInteger();
    // touched only on the sender thread
    private final Map<String, Long> webhookReadyAt = new HashMap<>();
    private long globalReadyAt;
    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.of("Asia/Seoul"));

    @Autowired
    public DiscordWebhookService(ReceiptStore store) {
        this(store, httpTransport());
    }

    DiscordWebhookService(ReceiptStore store, Transport transport) {
        this.store = store;
        this.transport = transport;
    }

    public void sendTestNotification(String webhookUrl, String username) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("웹훅 URL이 등록되어 있지 않습니다.");
        }
        String body = """
        {
          "embeds": [
            {
              "title": "🧪 [폼림픽] 디스코드 웹훅 연동 성공!",
              "description": "**%s**님의 디스코드 웹훅이 정상적으로 등록되었습니다.\\n앞으로 참여한 폼림픽이 마감되면 최종 순위와 접수 기록이 이 채널로 자동 전송됩니다.",
              "color": 15553206,
              "footer": {
                "text": "formlimpic · JUST FOR PRACTICE"
              }
            }
          ]
        }
        """.formatted(escapeJson(username));

        sendRaw(webhookUrl, body);
    }

    @Scheduled(fixedDelay = 1000)
    public void checkExpiredFormsAndNotify() {
        List<ReceiptStore.Form> expiredForms = store.unnotifiedExpiredForms();
        for (ReceiptStore.Form form : expiredForms) {
            try {
                notifyFormResults(form);
            } catch (Exception e) {
                log.warn("Failed to notify results for form {}: {}", form.id(), e.getMessage());
            } finally {
                store.markFormNotified(form.id());
            }
        }
    }

    private void notifyFormResults(ReceiptStore.Form form) {
        List<ReceiptStore.Receipt> results = store.results(form.id());
        int total = results.size();

        for (int i = 0; i < results.size(); i++) {
            int rank = i + 1;
            ReceiptStore.Receipt r = results.get(i);
            store.ticketById(r.ticketId()).ifPresent(ticket -> {
                store.findUserById(ticket.owner()).ifPresent(user -> {
                    String webhookUrl = user.discordWebhookUrl();
                    if (webhookUrl != null && !webhookUrl.isBlank()) {
                        sendResultWebhook(webhookUrl, form, user, r, rank, total);
                    }
                });
            });
        }
    }

    private void sendResultWebhook(String webhookUrl, ReceiptStore.Form form, ReceiptStore.User user, ReceiptStore.Receipt receipt, int rank, int total) {
        String medal = rank == 1 ? "🥇 " : rank == 2 ? "🥈 " : rank == 3 ? "🥉 " : "🏆 ";
        String rankText = "%s%d위 / 총 %d명".formatted(medal, rank, total);
        String formattedTime = KST_FORMATTER.format(receipt.receivedAt());
        String entryType = receipt.early() ? "조기 제출 (정상 신청자 뒤 배정)" : "정상 접수";

        String body = """
        {
          "embeds": [
            {
              "title": "⏱️ [폼림픽] 결과 발표 알림!",
              "color": 15553206,
              "fields": [
                { "name": "폼림픽 제목", "value": "%s", "inline": false },
                { "name": "최종 순위", "value": "**%s**", "inline": true },
                { "name": "멤버십 코드", "value": "`%s`", "inline": true },
                { "name": "접수 시각 (KST)", "value": "%s (%s)", "inline": false },
                { "name": "신청자 이름", "value": "%s", "inline": true }
              ],
              "footer": {
                "text": "formlimpic · JUST FOR PRACTICE"
              }
            }
          ]
        }
        """.formatted(
                escapeJson(form.title()),
                escapeJson(rankText),
                escapeJson(receipt.code()),
                escapeJson(formattedTime),
                escapeJson(entryType),
                escapeJson(receipt.name())
        );

        sendRaw(webhookUrl, body);
    }

    private void sendRaw(String webhookUrl, String jsonBody) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            log.warn("Discord queue is full ({} pending); dropping a notification", MAX_PENDING);
            return;
        }
        submit(new Delivery(webhookUrl, jsonBody, 0), 0);
    }

    /** Notifications queued or being retried. */
    int pending() { return pending.get(); }

    @PreDestroy
    void shutdown() {
        int left = pending.get();
        if (left > 0) log.warn("Discarding {} undelivered Discord notifications on shutdown", left);
        sender.shutdownNow();
    }

    private void submit(Delivery d, long delayMs) {
        try {
            sender.schedule(() -> deliver(d), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet(); // shutting down
        }
    }

    private void deliver(Delivery d) {
        long now = System.currentTimeMillis();
        Long webhookReady = webhookReadyAt.get(d.url());
        if (webhookReady != null && webhookReady <= now) webhookReadyAt.remove(d.url());
        long ready = Math.max(globalReadyAt, webhookReady == null ? 0 : webhookReady);
        if (ready > now) { submit(d, ready - now); return; }

        globalReadyAt = now + MIN_INTERVAL_MS;
        Delivery retry = null;
        long retryDelay = 0;
        try {
            Response r = transport.post(d.url(), d.body());
            long resetAfter = millis(r.header("X-RateLimit-Reset-After"));
            if ("0".equals(r.header("X-RateLimit-Remaining")) && resetAfter > 0) webhookReadyAt.put(d.url(), now + resetAfter);
            if (r.status() == 429) {
                long wait = Math.min(MAX_RETRY_AFTER_MS, retryAfterMs(r));
                if ("true".equalsIgnoreCase(r.header("X-RateLimit-Global")) || "global".equalsIgnoreCase(r.header("X-RateLimit-Scope"))) {
                    globalReadyAt = now + wait;
                } else {
                    webhookReadyAt.put(d.url(), now + wait);
                }
                if (d.attempt() < MAX_ATTEMPTS) { retry = d.next(); retryDelay = wait; }
                else log.warn("Discord kept rate limiting a webhook; dropping the notification");
            } else if (r.status() >= 500) {
                if (d.attempt() < MAX_ATTEMPTS) { retry = d.next(); retryDelay = backoff(d.attempt()); }
                else log.warn("Discord webhook failed with {} after {} attempts", r.status(), MAX_ATTEMPTS + 1);
            } else if (r.status() >= 400) {
                log.warn("Discord rejected a webhook delivery with {}", r.status()); // deleted webhook etc.: retrying won't help
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (d.attempt() < MAX_ATTEMPTS) { retry = d.next(); retryDelay = backoff(d.attempt()); }
            else log.warn("Discord webhook delivery failed: {}", e.getMessage());
        }
        if (retry != null) submit(retry, retryDelay);
        else pending.decrementAndGet();
    }

    private static long backoff(int attempt) { return 1000L << Math.min(attempt, 5); }

    private static long retryAfterMs(Response r) {
        long ms = millis(r.header("Retry-After"));
        if (ms > 0) return ms;
        Matcher m = RETRY_AFTER_BODY.matcher(r.body() == null ? "" : r.body());
        if (m.find()) ms = millis(m.group(1));
        return ms > 0 ? ms : 1000;
    }

    // Discord sends rate-limit times in (fractional) seconds
    private static long millis(String seconds) {
        if (seconds == null || seconds.isBlank()) return 0;
        try {
            return (long) Math.ceil(Double.parseDouble(seconds.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    record Delivery(String url, String body, int attempt) {
        Delivery next() { return new Delivery(url, body, attempt + 1); }
    }
    record Response(int status, Map<String, String> headers, String body) {
        String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            return null;
        }
    }
    interface Transport {
        Response post(String url, String body) throws Exception;
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, body) -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new HashMap<>();
            res.headers().map().forEach((k, v) -> { if (!v.isEmpty()) headers.put(k, v.get(0)); });
            return new Response(res.statusCode(), headers, res.body());
        };
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
