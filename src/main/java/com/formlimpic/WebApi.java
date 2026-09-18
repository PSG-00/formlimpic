package com.formlimpic;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
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
}

@RestController
@RequestMapping("/api")
public class WebApi {
    private final ReceiptStore store;
    WebApi(ReceiptStore store) { this.store = store; }
    record NewForm(String title, String content, Instant startsAt, Instant expiresAt) {}
    record Answer(String name, String phone) {}
    record Row(int rank, String name, String membershipCode, Instant receivedAt, boolean early) {}
    @ModelAttribute void headers(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod()) && !"formlimpic".equals(request.getHeader("X-Formlimpic"))) throw new IllegalArgumentException("잘못된 요청입니다.");
    }
    private String owner(HttpServletRequest req, HttpServletResponse res) {
        if (req.getCookies() != null) for (Cookie cookie : req.getCookies()) {
            if (cookie.getName().equals("formlimpic_owner") && cookie.getValue().matches("[0-9a-f-]{36}")) return cookie.getValue();
        }
        String id = UUID.randomUUID().toString(); Cookie cookie = new Cookie("formlimpic_owner", id);
        cookie.setHttpOnly(true); cookie.setSecure(req.isSecure()); cookie.setPath("/"); cookie.setMaxAge(31536000); cookie.setAttribute("SameSite", "Strict"); res.addCookie(cookie); return id;
    }
    @GetMapping("/forms") Object list(HttpServletRequest req, HttpServletResponse res) { owner(req, res); return Map.of("forms", store.forms(), "serverTime", store.now()); }
    @PostMapping("/forms") Object create(@RequestBody NewForm f) { return store.create(f.title(), f.content(), f.startsAt(), f.expiresAt()); }
    @GetMapping("/forms/{id}") Object detail(@PathVariable String id, HttpServletRequest req, HttpServletResponse res) {
        Map<String, Object> data = new HashMap<>(); data.put("form", store.form(id)); data.put("serverTime", store.now()); data.put("mine", store.mine(id, owner(req, res))); return data;
    }
    @PostMapping("/forms/{id}/ticket") Object ticket(@PathVariable String id, HttpServletRequest req, HttpServletResponse res) {
        ReceiptStore.Ticket t = store.ticket(id, owner(req, res)); return Map.of("code", t.code());
    }
    @PostMapping("/forms/{id}/submissions") Object submit(@PathVariable String id, @RequestBody Answer a, HttpServletRequest req, HttpServletResponse res) { return store.submit(id, owner(req, res), a.name(), a.phone()); }
    @GetMapping("/forms/{id}/results") Object results(@PathVariable String id) {
        List<Row> rows = new ArrayList<>(); for (ReceiptStore.Receipt r : store.results(id)) rows.add(new Row(rows.size() + 1, r.name(), r.code(), r.receivedAt(), r.early())); return rows;
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message", e.getMessage())); }
}
