package com.formlimpic;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Stamps the server arrival time as early as possible (before rate limiting and body parsing).
 * Submissions are ranked by this time plus the simulated delay, so later processing order does not matter.
 *
 * Under a burst the request can wait for a Tomcat thread before this filter runs, so when enabled the stamp
 * Caddy adds on receipt is used instead (Caddyfile: header_up X-Arrived-At {time.now.unix_ms}).
 */
@Component
@Order(0)
public class ArrivalTimeFilter implements Filter {
    static final String ATTRIBUTE = "formlimpic.arrivedAt";
    static final String HEADER = "X-Arrived-At";
    private final ReceiptStore store;
    private final boolean trustProxyArrival;

    ArrivalTimeFilter(ReceiptStore store, @Value("${formlimpic.trust-proxy-arrival:false}") boolean trustProxyArrival) {
        this.store = store;
        this.trustProxyArrival = trustProxyArrival;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setAttribute(ATTRIBUTE, arrival(request, store.now()));
        chain.doFilter(request, response);
    }

    Instant arrival(ServletRequest request, Instant now) {
        // Only when Caddy overwrites the header (otherwise a client could forge an earlier arrival)
        if (!trustProxyArrival || !(request instanceof HttpServletRequest req) || !RateLimitFilter.isLoopback(req.getRemoteAddr())) {
            return now;
        }
        String stamp = req.getHeader(HEADER);
        if (stamp == null || stamp.isBlank()) return now;
        try {
            Instant t = Instant.ofEpochMilli(Long.parseLong(stamp.trim()));
            // Caddy runs on this host: anything outside a few seconds is a misconfiguration, not an arrival
            if (t.isAfter(now.plusSeconds(1)) || t.isBefore(now.minusSeconds(30))) return now;
            return t;
        } catch (NumberFormatException e) {
            return now;
        }
    }
}
