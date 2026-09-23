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
        filter = new RateLimitFilter();
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
            req.addHeader("X-Formlimpic-Bypass", "formlimpic-loadtest-pass");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertEquals(200, res.getStatus());
        }
    }
}
