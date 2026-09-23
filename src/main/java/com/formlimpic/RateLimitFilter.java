package com.formlimpic;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 429 Too Many Requests Multi-tier Rate Limiter
 * 1. Second Window : Blocks bursts exceeding 15 req/sec (temporary 1-second pause).
 * 2. Minute Window : Detects persistent bots exceeding 600 req/min and bans IP for 1 hour (hard ban).
 * Keeps normal participants fast while protecting GCP resources and egress bandwidth.
 */
@Component
@Order(1)
public class RateLimitFilter implements Filter {
    private static final int MAX_REQUESTS_PER_SECOND = 15;
    private static final int MAX_REQUESTS_PER_MINUTE = 600;
    private static final long BAN_DURATION_MS = 60 * 60 * 1000L; // 1시간 (3,600,000 ms)

    private static class SecondCounter {
        long timestampSec;
        final AtomicInteger count = new AtomicInteger(0);

        SecondCounter(long timestampSec) {
            this.timestampSec = timestampSec;
            this.count.set(1);
        }
    }

    private static class MinuteCounter {
        long timestampMin;
        final AtomicInteger count = new AtomicInteger(0);

        MinuteCounter(long timestampMin) {
            this.timestampMin = timestampMin;
            this.count.set(1);
        }
    }

    private final ConcurrentHashMap<String, SecondCounter> secondCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MinuteCounter> minuteCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private long lastCleanUp = System.currentTimeMillis();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }

        String uri = req.getRequestURI();
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // Allow load-test bypass with designated secret header
        if ("formlimpic-loadtest-pass".equals(req.getHeader("X-Formlimpic-Bypass"))) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(req);
        long now = System.currentTimeMillis();

        // Tier 1: Check if IP is currently under 1-hour Hard Ban
        Long banExpiry = bannedUntil.get(clientIp);
        if (banExpiry != null) {
            if (now < banExpiry) {
                long remainingSec = (banExpiry - now + 999) / 1000;
                long remainingMin = (remainingSec + 59) / 60;
                res.setStatus(429);
                res.setHeader("Retry-After", String.valueOf(remainingSec));
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(String.format("{\"message\":\"비정상적인 요청이 지속 감지되어 일시 차단되었습니다. (남은 시간: 약 %d분)\"}", remainingMin));
                return;
            } else {
                bannedUntil.remove(clientIp);
            }
        }

        // Tier 2: Check persistent bot activity (Max 600 req/min)
        long nowMin = now / 60000L;
        MinuteCounter minCounter = minuteCounters.compute(clientIp, (ip, existing) -> {
            if (existing == null || existing.timestampMin != nowMin) {
                return new MinuteCounter(nowMin);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (minCounter.count.get() > MAX_REQUESTS_PER_MINUTE) {
            long newBanExpiry = now + BAN_DURATION_MS;
            bannedUntil.put(clientIp, newBanExpiry);
            res.setStatus(429);
            res.setHeader("Retry-After", "3600");
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"비정상적인 과도한 요청(분당 600회 초과)이 감지되어 1시간 동안 접속이 차단됩니다.\"}");
            return;
        }

        // Tier 3: Check burst clicks per second (Max 15 req/sec)
        long nowSec = now / 1000L;
        SecondCounter secCounter = secondCounters.compute(clientIp, (ip, existing) -> {
            if (existing == null || existing.timestampSec != nowSec) {
                return new SecondCounter(nowSec);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (secCounter.count.get() > MAX_REQUESTS_PER_SECOND) {
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
            return;
        }

        // Periodic cleanup for stale entries
        if (now - lastCleanUp > 60000) {
            lastCleanUp = now;
            secondCounters.entrySet().removeIf(entry -> entry.getValue().timestampSec < nowSec - 2);
            minuteCounters.entrySet().removeIf(entry -> entry.getValue().timestampMin < nowMin - 1);
            bannedUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        }

        chain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest req) {
        String cfIp = req.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp.trim();

        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int commaIdx = xff.indexOf(',');
            return (commaIdx != -1 ? xff.substring(0, commaIdx) : xff).trim();
        }

        return req.getRemoteAddr();
    }
}
