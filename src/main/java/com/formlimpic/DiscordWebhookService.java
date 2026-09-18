package com.formlimpic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
@EnableScheduling
public class DiscordWebhookService {
    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookService.class);
    private final ReceiptStore store;
    private final HttpClient http;
    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.of("Asia/Seoul"));

    public DiscordWebhookService(ReceiptStore store) {
        this.store = store;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("Discord webhook delivery failed: {}", ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Failed to create webhook request: {}", e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
