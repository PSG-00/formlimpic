package com.formlimpic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KakaoAuthTests {
    private WebApi webApi;
    private ReceiptStore store;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() throws IOException {
        Path tempDir = Files.createTempDirectory("formlimpic-kakao-test");
        store = new ReceiptStore(tempDir.resolve("receipts.log"), Clock.systemUTC());
        encoder = new PasswordEncoder() {
            @Override public String encode(CharSequence rawPassword) { return "hashed_" + rawPassword; }
            @Override public boolean matches(CharSequence rawPassword, String encodedPassword) { return encodedPassword.equals("hashed_" + rawPassword); }
        };
    }

    @Test
    void kakaoLoginUrlReturnsUnavailableWhenNotConfigured() {
        KakaoAuthService service = new KakaoAuthService("", "");
        webApi = new WebApi(store, encoder, null, null, service);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("formlimpic.rescene.online");
        req.setScheme("https");

        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) webApi.kakaoLoginUrl(req);
        assertEquals(false, res.get("available"));
    }

    @Test
    void kakaoLoginUrlReturnsAuthorizeUrlWhenConfigured() {
        KakaoAuthService service = new KakaoAuthService("test_kakao_client_id", "test_secret");
        webApi = new WebApi(store, encoder, null, null, service);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setServerName("formlimpic.rescene.online");
        req.setScheme("https");
        req.addHeader("Host", "formlimpic.rescene.online");

        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) webApi.kakaoLoginUrl(req);
        assertEquals(true, res.get("available"));
        String url = (String) res.get("url");
        assertTrue(url.contains("client_id=test_kakao_client_id"));
        assertTrue(url.contains("redirect_uri="));
    }

    @Test
    void kakaoCallbackRedirectsToLoginOnError() throws IOException {
        KakaoAuthService service = new KakaoAuthService("test_kakao_client_id", "test_secret");
        webApi = new WebApi(store, encoder, null, null, service);

        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        webApi.kakaoCallback(null, "access_denied", req, res);
        assertEquals("/#login?kakaoError=cancel", res.getRedirectedUrl());
    }

    @Test
    void registerOrFindOauthUserCreatesAndReusesUser() {
        ReceiptStore.User user1 = store.registerOrFindOauthUser("kakao_12345678", "hash1");
        assertNotNull(user1);
        assertEquals("kakao_12345678", user1.username());
        assertNotNull(user1.membershipCode());

        ReceiptStore.User user2 = store.registerOrFindOauthUser("kakao_12345678", "hash2");
        assertEquals(user1.id(), user2.id());
        assertEquals(user1.membershipCode(), user2.membershipCode());
    }
}
