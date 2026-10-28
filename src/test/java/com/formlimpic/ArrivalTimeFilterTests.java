package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ArrivalTimeFilterTests {
    @TempDir
    Path dir;

    private static MockHttpServletRequest request(String remoteAddr, String stamp) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/forms/f/submissions");
        req.setRemoteAddr(remoteAddr);
        if (stamp != null) req.addHeader(ArrivalTimeFilter.HEADER, stamp);
        return req;
    }

    @Test
    void usesCaddyStampOnlyWhenEnabledAndFromLoopback() throws Exception {
        ReceiptStoreTests.TestClock clock = new ReceiptStoreTests.TestClock();
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), clock)) {
            Instant now = clock.value;
            String stamp = String.valueOf(now.minusMillis(850).toEpochMilli()); // waited 850ms for a thread

            ArrivalTimeFilter enabled = new ArrivalTimeFilter(store, true);
            assertEquals(now.minusMillis(850), enabled.arrival(request("127.0.0.1", stamp), now));
            assertEquals(now, enabled.arrival(request("203.0.113.5", stamp), now), "forged header from a non-proxy peer");
            assertEquals(now, enabled.arrival(request("127.0.0.1", null), now), "no header: own stamp");

            ArrivalTimeFilter disabled = new ArrivalTimeFilter(store, false);
            assertEquals(now, disabled.arrival(request("127.0.0.1", stamp), now), "not enabled: Caddy may not overwrite it");
        }
    }

    @Test
    void ignoresImplausibleStamps() throws Exception {
        ReceiptStoreTests.TestClock clock = new ReceiptStoreTests.TestClock();
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), clock)) {
            Instant now = clock.value;
            ArrivalTimeFilter filter = new ArrivalTimeFilter(store, true);
            for (String bad : new String[]{"abc", "{time.now.unix_ms}", String.valueOf(now.plusSeconds(5).toEpochMilli()), String.valueOf(now.minusSeconds(60).toEpochMilli())}) {
                assertEquals(now, filter.arrival(request("127.0.0.1", bad), now), bad);
            }
        }
    }

    @Test
    void stampsRequestAttribute() throws Exception {
        ReceiptStoreTests.TestClock clock = new ReceiptStoreTests.TestClock();
        try (ReceiptStore store = new ReceiptStore(dir.resolve("journal"), clock)) {
            MockHttpServletRequest req = request("127.0.0.1", null);
            new ArrivalTimeFilter(store, false).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
            assertEquals(clock.value, req.getAttribute(ArrivalTimeFilter.ATTRIBUTE));
        }
    }
}
