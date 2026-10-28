package com.formlimpic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTests {
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter("test-bypass-token");
    }

    @Test
    void allowsNormalRequestsUnder15PerSecond() throws Exception {
        for (int i = 0; i < 15; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/forms");
            req.setRemoteAddr("192.168.1.100");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void blocks16thRequestInSameSecondWithTemporary429() throws Exception {
        for (int i = 0; i < 15; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/forms");
            req.setRemoteAddr("192.168.1.101");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest req16 = new MockHttpServletRequest("GET", "/api/forms");
        req16.setRemoteAddr("192.168.1.101");
        MockHttpServletResponse res16 = new MockHttpServletResponse();
        filter.doFilter(req16, res16, new MockFilterChain());

        assertEquals(429, res16.getStatus());
        assertEquals("1", res16.getHeader("Retry-After"));
        assertTrue(res16.getContentAsString().contains("요청이 너무 많습니다"));
    }

    @Test
    void triggers1HourBanWhenExceeding600RequestsPerMinute() throws Exception {
        String testIp = "192.168.1.102";

        // Simulate 601 requests within the same minute
        // (Bypass second limit by using test or direct loop)
        for (int i = 1; i <= 600; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/forms");
            req.setRemoteAddr(testIp);
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // 601st request should trigger 1-hour ban
        MockHttpServletRequest req601 = new MockHttpServletRequest("GET", "/api/forms");
        req601.setRemoteAddr(testIp);
        MockHttpServletResponse res601 = new MockHttpServletResponse();
        filter.doFilter(req601, res601, new MockFilterChain());

        assertEquals(429, res601.getStatus());
        assertTrue(res601.getContentAsString().contains("1시간 동안 접속이 차단됩니다"));

        // Subsequent request immediately rejected by ban
        MockHttpServletRequest reqNext = new MockHttpServletRequest("GET", "/api/forms");
        reqNext.setRemoteAddr(testIp);
        MockHttpServletResponse resNext = new MockHttpServletResponse();
        filter.doFilter(reqNext, resNext, new MockFilterChain());

        assertEquals(429, resNext.getStatus());
        assertTrue(resNext.getContentAsString().contains("비정상적인 요청이 지속 감지되어 일시 차단되었습니다"));
    }

    @Test
    void bypassHeaderBypassesBothRateLimitAndBan() throws Exception {
        String testIp = "192.168.1.103";

        // Even with 700 requests, all should pass with bypass header
        for (int i = 0; i < 700; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/forms");
            req.setRemoteAddr(testIp);
            req.addHeader("X-Formlimpic-Bypass", "test-bypass-token");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void bypassRequiresMatchingConfiguredToken() throws Exception {
        // Wrong token (the old hard-coded value) does not bypass
        assertEquals(429, sixteenthStatus(filter, "192.168.1.104", "formlimpic-loadtest-pass"));

        // No token configured on the server: bypass disabled even for an empty header
        RateLimitFilter noBypass = new RateLimitFilter("");
        assertEquals(429, sixteenthStatus(noBypass, "192.168.1.105", ""));
    }

    private static int sixteenthStatus(RateLimitFilter f, String ip, String bypassHeader) throws Exception {
        int status = 0;
        for (int i = 0; i < 16; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/forms");
            req.setRemoteAddr(ip);
            req.addHeader("X-Formlimpic-Bypass", bypassHeader);
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req, res, new MockFilterChain());
            status = res.getStatus();
        }
        return status;
    }

    @Test
    void resolveClientIpPrioritizesXRealIpOverOtherHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "203.0.113.195");
        req.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.195");
        req.addHeader("CF-Connecting-IP", "198.51.100.2");
        req.setRemoteAddr("127.0.0.1");

        assertEquals("203.0.113.195", filter.resolveClientIp(req));
    }

    @Test
    void resolveClientIpPreventsSpoofingByTakingLastXffIp() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // Attacker attempts spoofing with fake IP in XFF, Caddy appends real IP at the end
        req.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 203.0.113.50");
        req.setRemoteAddr("127.0.0.1");

        assertEquals("203.0.113.50", filter.resolveClientIp(req));
    }

    @Test
    void resolveClientIpFallsBackToCfConnectingIpAndRemoteAddr() {
        MockHttpServletRequest reqCf = new MockHttpServletRequest();
        reqCf.addHeader("CF-Connecting-IP", "104.28.1.1");
        reqCf.setRemoteAddr("127.0.0.1");
        assertEquals("104.28.1.1", filter.resolveClientIp(reqCf));

        MockHttpServletRequest reqDirect = new MockHttpServletRequest();
        reqDirect.setRemoteAddr("172.16.0.5");
        assertEquals("172.16.0.5", filter.resolveClientIp(reqDirect));
    }

    @Test
    void resolveClientIpIgnoresProxyHeadersFromNonLoopbackPeer() {
        // Direct hit on :8080 from the internet with forged headers
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "1.1.1.1");
        req.addHeader("X-Forwarded-For", "2.2.2.2");
        req.addHeader("CF-Connecting-IP", "3.3.3.3");
        req.setRemoteAddr("203.0.113.77");

        assertEquals("203.0.113.77", filter.resolveClientIp(req));
    }

    @Test
    void resolveClientIpTrustsIpv6LoopbackProxy() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "203.0.113.9");
        req.setRemoteAddr("0:0:0:0:0:0:0:1");

        assertEquals("203.0.113.9", filter.resolveClientIp(req));
    }
}
