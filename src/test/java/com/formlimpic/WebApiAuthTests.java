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

import static org.junit.jupiter.api.Assertions.*;
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
        boolean rateLimited = false;
        for (int i = 0; i < 30; i++) {
            var result = mockMvc.perform(get("/api/forms").with(req -> {
                req.setRemoteAddr(clientIp);
                return req;
            })).andReturn();
            if (result.getResponse().getStatus() == 429) {
                rateLimited = true;
                assertTrue(result.getResponse().getContentAsString().contains("요청이 너무 많습니다"));
                break;
            }
        }
        assertTrue(rateLimited, "Rate limiter should have triggered 429 within 30 rapid requests");
    }

    @Test
    void adminFeaturesRoleToggleAndDelete() throws Exception {
        ReceiptStore store = new ReceiptStore(dir.resolve("admin_journal"), Clock.systemUTC());
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        DiscordWebhookService discordService = new DiscordWebhookService(store);
        WebApi api = new WebApi(store, encoder, discordService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(api).build();

        MockHttpSession normalSession = new MockHttpSession();
        MockHttpSession adminSession = new MockHttpSession();

        // 1. Normal user signup -> role is USER
        mockMvc.perform(post("/api/auth/signup")
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"regular_user\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("regular_user"))
                .andExpect(jsonPath("$.user.role").value("USER"));

        // 2. Signup with reserved username 'admin' -> 400 Bad Request
        mockMvc.perform(post("/api/auth/signup")
                        .session(adminSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("해당 아이디는 시스템 예약어로 사용할 수 없습니다."));

        // 2-1. Admin account initialized via AdminInitializer with Google-style random password
        AdminInitializer initializer = new AdminInitializer(store, encoder);
        String randomPassword = initializer.generateGoogleStylePassword();
        assertTrue(randomPassword.matches("^[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}$"));
        store.registerUser("admin", encoder.encode(randomPassword));

        // 2-2. Admin login with generated password
        mockMvc.perform(post("/api/auth/login")
                        .session(adminSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + randomPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));

        // 3. Normal user creates form when toggle is OFF (adminCreated should be false)
        String start = Instant.now().plusSeconds(3600).toString();
        String end = Instant.now().plusSeconds(7200).toString();
        mockMvc.perform(post("/api/forms")
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"유저 폼\",\"content\":\"설명\",\"startsAt\":\"" + start + "\",\"expiresAt\":\"" + end + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("유저 폼"))
                .andExpect(jsonPath("$.adminCreated").value(false));

        String formId = store.forms().get(0).id();

        // 4. Normal user tries to toggle setting -> 400 Bad Request
        mockMvc.perform(put("/api/admin/settings/form-creation")
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminOnly\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        // 5. Admin toggles setting to ON
        mockMvc.perform(put("/api/admin/settings/form-creation")
                        .session(adminSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminOnlyFormCreation").value(true));

        // 6. Check /api/forms returns adminOnlyFormCreation: true
        mockMvc.perform(get("/api/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminOnlyFormCreation").value(true));

        // 7. Normal user tries to create form when toggle is ON -> 400 Bad Request
        mockMvc.perform(post("/api/forms")
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"차단될 폼\",\"content\":\"설명\",\"startsAt\":\"" + start + "\",\"expiresAt\":\"" + end + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("현재 관리자만 폼림픽을 개설할 수 있습니다."));

        // 8. Admin creates form when toggle is ON (adminCreated should be true)
        mockMvc.perform(post("/api/forms")
                        .session(adminSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"관리자 폼\",\"content\":\"설명\",\"startsAt\":\"" + start + "\",\"expiresAt\":\"" + end + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("관리자 폼"))
                .andExpect(jsonPath("$.adminCreated").value(true));

        // 9. Normal user tries to delete form -> 400 Bad Request
        mockMvc.perform(delete("/api/forms/" + formId)
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        // 10. Admin deletes form -> 200 OK
        mockMvc.perform(delete("/api/forms/" + formId)
                        .session(adminSession)
                        .header("X-Formlimpic", "formlimpic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.deletedFormId").value(formId));

        // 11. Verify form is deleted
        assertEquals(1, store.forms().size());
        assertEquals("관리자 폼", store.forms().get(0).title());
        String adminFormId = store.forms().get(0).id();

        // 12. Security Audit 1: Normal user tries to delete admin's form -> 400 Bad Request
        mockMvc.perform(delete("/api/forms/" + adminFormId)
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        // 13. Security Audit 2: Normal user attempts to spoof adminCreated badge via raw JSON payload
        // First admin turns creation toggle OFF to allow normal user creation
        mockMvc.perform(put("/api/admin/settings/form-creation")
                        .session(adminSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminOnly\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/forms")
                        .session(normalSession)
                        .header("X-Formlimpic", "formlimpic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"사칭 시도 폼\",\"content\":\"설명\",\"startsAt\":\"" + start + "\",\"expiresAt\":\"" + end + "\",\"adminCreated\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("사칭 시도 폼"))
                .andExpect(jsonPath("$.adminCreated").value(false)); // Forced false by server!
    }
}
