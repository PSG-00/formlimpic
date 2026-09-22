package com.formlimpic;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

@Configuration
class StoreConfiguration {
    @Bean(destroyMethod = "close") ReceiptStore receiptStore(@Value("${formlimpic.journal}") String path) throws IOException {
        return new ReceiptStore(Path.of(path), Clock.systemUTC());
    }
    @Bean PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

@RestController
@RequestMapping("/api")
public class WebApi {
    private final ReceiptStore store;
    private final PasswordEncoder encoder;
    private final DiscordWebhookService discordService;

    WebApi(ReceiptStore store, PasswordEncoder encoder, DiscordWebhookService discordService) {
        this.store = store;
        this.encoder = encoder;
        this.discordService = discordService;
    }

    record NewForm(String title, String content, Instant startsAt, Instant expiresAt, Boolean hasBubble) {}
    record Answer(String name, String birthDate, String phone, String bubble) {
        public Answer(String name, String phone) {
            this(name, "", phone, "");
        }
    }
    record Row(int rank, String name, String membershipCode, Instant receivedAt, boolean early) {}
    record AuthRequest(String username, String password) {}
    record WebhookRequest(String webhookUrl) {}
    record UserProfile(String id, String username, String membershipCode, String discordWebhookUrl) implements java.io.Serializable {}

    @ModelAttribute void headers(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod()) && !"formlimpic".equals(request.getHeader("X-Formlimpic"))) throw new IllegalArgumentException("잘못된 요청입니다.");
    }

    private UserProfile current(HttpSession session) {
        if (session == null) return null;
        return (UserProfile) session.getAttribute("auth_user");
    }

    private UserProfile requireUser(HttpSession session) {
        UserProfile u = current(session);
        if (u == null) throw new IllegalArgumentException("로그인이 필요합니다.");
        return u;
    }

    private String owner(HttpServletRequest req, HttpServletResponse res) {
        HttpSession session = req.getSession(false);
        UserProfile u = current(session);
        if (u != null) return u.id();
        if (req.getCookies() != null) for (Cookie cookie : req.getCookies()) {
            if (cookie.getName().equals("formlimpic_owner") && cookie.getValue().matches("[0-9a-f-]{36}")) return cookie.getValue();
        }
        String id = UUID.randomUUID().toString(); Cookie cookie = new Cookie("formlimpic_owner", id);
        cookie.setHttpOnly(true); cookie.setSecure(req.isSecure()); cookie.setPath("/"); cookie.setMaxAge(31536000); cookie.setAttribute("SameSite", "Strict"); res.addCookie(cookie); return id;
    }

    @PostMapping("/auth/signup")
    Object signup(@RequestBody AuthRequest req, HttpSession session) {
        if (req.username() == null || !req.username().trim().matches("^[a-zA-Z0-9_]{3,20}$")) {
            throw new IllegalArgumentException("아이디는 3~20자의 영문, 숫자, 밑줄(_)만 가능합니다.");
        }
        if (req.password() == null || req.password().length() < 6 || req.password().length() > 50) {
            throw new IllegalArgumentException("비밀번호는 6~50자 사이여야 합니다.");
        }
        ReceiptStore.User user = store.registerUser(req.username().trim(), encoder.encode(req.password()));
        UserProfile profile = new UserProfile(user.id(), user.username(), user.membershipCode(), user.discordWebhookUrl());
        session.setAttribute("auth_user", profile);
        return Map.of("user", profile);
    }

    @PostMapping("/auth/login")
    Object login(@RequestBody AuthRequest req, HttpSession session) {
        if (req.username() == null || req.password() == null) throw new IllegalArgumentException("아이디와 비밀번호를 입력해주세요.");
        ReceiptStore.User user = store.findUserByUsername(req.username().trim())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다."));
        if (!encoder.matches(req.password(), user.passwordHash())) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.");
        }
        UserProfile profile = new UserProfile(user.id(), user.username(), user.membershipCode(), user.discordWebhookUrl());
        session.setAttribute("auth_user", profile);
        return Map.of("user", profile);
    }

    @PutMapping("/my/webhook")
    Object updateWebhook(@RequestBody WebhookRequest req, HttpSession session) {
        UserProfile user = requireUser(session);
        ReceiptStore.User updated = store.updateWebhook(user.id(), req.webhookUrl());
        UserProfile newProfile = new UserProfile(updated.id(), updated.username(), updated.membershipCode(), updated.discordWebhookUrl());
        session.setAttribute("auth_user", newProfile);
        return Map.of("ok", true, "user", newProfile);
    }

    @PostMapping("/my/webhook/test")
    Object testWebhook(@RequestBody(required = false) WebhookRequest req, HttpSession session) {
        UserProfile user = requireUser(session);
        String url = (req != null && req.webhookUrl() != null && !req.webhookUrl().isBlank())
                ? req.webhookUrl().trim()
                : user.discordWebhookUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("디스코드 웹훅 URL을 먼저 입력해주세요.");
        }
        discordService.sendTestNotification(url, user.username());
        return Map.of("ok", true, "message", "테스트 알림이 전송되었습니다. 디스코드 채널을 확인하세요!");
    }

    @PostMapping("/auth/logout")
    Object logout(HttpSession session) {
        if (session != null) session.invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/auth/me")
    Object me(HttpSession session) {
        UserProfile user = current(session);
        if (user == null) return Map.of("loggedIn", false);
        return Map.of("loggedIn", true, "user", user);
    }

    @GetMapping("/my/forms")
    Object myForms(HttpSession session) {
        UserProfile user = requireUser(session);
        return Map.of("forms", store.myForms(user.id()));
    }

    @GetMapping("/my/submissions")
    Object mySubmissions(HttpSession session) {
        UserProfile user = requireUser(session);
        return Map.of("submissions", store.mySubmissions(user.id()));
    }

    @GetMapping("/forms") Object list(HttpServletRequest req, HttpServletResponse res) { owner(req, res); return Map.of("forms", store.forms(), "serverTime", store.now()); }

    @PostMapping("/forms")
    Object create(@RequestBody NewForm f, HttpSession session) {
        UserProfile user = requireUser(session);
        boolean hasBubble = f.hasBubble() != null && f.hasBubble();
        return store.create(user.id(), f.title(), f.content(), f.startsAt(), f.expiresAt(), hasBubble);
    }

    @GetMapping("/forms/{id}") Object detail(@PathVariable String id, HttpServletRequest req, HttpServletResponse res) {
        Map<String, Object> data = new HashMap<>(); data.put("form", store.form(id)); data.put("serverTime", store.now()); data.put("mine", store.mine(id, owner(req, res))); return data;
    }

    @PostMapping("/forms/{id}/ticket")
    Object ticket(@PathVariable String id, HttpSession session) {
        UserProfile user = requireUser(session);
        ReceiptStore.Ticket t = store.ticket(id, user.id());
        return Map.of("code", t.code());
    }

    @PostMapping("/forms/{id}/submissions")
    Object submit(@PathVariable String id, @RequestBody Answer a, HttpSession session) {
        UserProfile user = requireUser(session);
        return store.submit(id, user.id(), a.name(), a.birthDate(), a.phone(), a.bubble());
    }

    @GetMapping("/forms/{id}/results") Object results(@PathVariable String id) {
        List<Row> rows = new ArrayList<>(); for (ReceiptStore.Receipt r : store.results(id)) rows.add(new Row(rows.size() + 1, r.name(), r.code(), r.receivedAt(), r.early())); return rows;
    }

    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message", e.getMessage())); }
}
