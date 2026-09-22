package com.formlimpic;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 429 Too Many Requests Rate Limiter
 * Blocks excessive click bursts and bots per IP while keeping normal participants fast.
 */
@Component
@Order(1)
public class RateLimitFilter implements Filter {
    private static final int MAX_REQUESTS_PER_SECOND = 15;

    private static class Counter {
        long timestamp;
        final AtomicInteger count = new AtomicInteger(0);

        Counter(long timestamp) {
            this.timestamp = timestamp;
            this.count.set(1);
        }
    }

    private final ConcurrentHashMap<String, Counter> ipCounters = new ConcurrentHashMap<>();
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
        long nowSec = System.currentTimeMillis() / 1000;

        Counter counter = ipCounters.compute(clientIp, (ip, existing) -> {
            if (existing == null || existing.timestamp != nowSec) {
                return new Counter(nowSec);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (counter.count.get() > MAX_REQUESTS_PER_SECOND) {
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanUp > 60000) {
            lastCleanUp = currentTime;
            ipCounters.entrySet().removeIf(entry -> entry.getValue().timestamp < nowSec - 2);
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
