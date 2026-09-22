package com.formlimpic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebApiAuthTests {
    @TempDir
    Path dir;

    @Test
    void authFlowAndMyPage() throws Exception {
        ReceiptStore store = new ReceiptStore(dir.resolve("auth_journal"), Clock.systemUTC());
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        DiscordWebhookService discordService = new DiscordWebhookService(store);
        WebApi api = new WebApi(store, encoder, discordService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(api).build();

        MockHttpSession session = new MockHttpSession();

        // 1. Initially unauthenticated
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(false));

        // 2. Signup
        mockMvc.perform(post("/api/auth/signup")
                        .session(session)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"john_doe\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("john_doe"))
                .andExpect(jsonPath("$.user.membershipCode").isString());

        // 3. Authenticated check
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedIn").value(true))
                .andExpect(jsonPath("$.user.username").value("john_doe"))
                .andExpect(jsonPath("$.user.membershipCode").isString());

        // 4. Update Webhook URL
        mockMvc.perform(put("/api/my/webhook")
                        .session(session)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookUrl\":\"https://discord.com/api/webhooks/123/abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.discordWebhookUrl").value("https://discord.com/api/webhooks/123/abc"));

        // 5. Create Form (authorized)
        String nowPlus1h = Instant.now().plusSeconds(3600).toString();
        String nowPlus2h = Instant.now().plusSeconds(7200).toString();
        mockMvc.perform(post("/api/forms")
                        .session(session)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"선착순 이벤트\",\"content\":\"상세 설명\",\"startsAt\":\"" + nowPlus1h + "\",\"expiresAt\":\"" + nowPlus2h + "\",\"hasBubble\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("선착순 이벤트"))
                .andExpect(jsonPath("$.hasBubble").value(true));

        // 6. My forms
        mockMvc.perform(get("/api/my/forms").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forms[0].title").value("선착순 이벤트"));

        // 7. Logout
        mockMvc.perform(post("/api/auth/logout")
                        .session(session)
                        .header("X-Formlimpic", "formlimpic"))
                .andExpect(status().isOk());

        // 7. Login with valid password
        mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"john_doe\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("john_doe"));

        // 8. Login with wrong password
        mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"john_doe\",\"password\":\"wrongpw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 일치하지 않습니다."));
    }

    @Test
    void rateLimiterBlocksExcessiveRequests() throws Exception {
        ReceiptStore store = new ReceiptStore(dir.resolve("ratelimit_journal"), Clock.systemUTC());
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        DiscordWebhookService discordService = new DiscordWebhookService(store);
        WebApi api = new WebApi(store, encoder, discordService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(api).addFilters(new RateLimitFilter()).build();

        String clientIp = "192.168.1.100";
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/api/forms").with(req -> {
                req.setRemoteAddr(clientIp);
                return req;
            })).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/forms").with(req -> {
            req.setRemoteAddr(clientIp);
            return req;
        })).andExpect(status().is(429))
           .andExpect(jsonPath("$.message").value("요청이 너무 많습니다. 잠시 후 다시 시도해주세요."));
    }
}
