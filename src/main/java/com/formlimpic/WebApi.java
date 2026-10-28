package com.formlimpic;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Configuration
class StoreConfiguration {
    @Bean(destroyMethod = "close") ReceiptStore receiptStore(@Value("${formlimpic.journal}") String path,
                                                             @Value("${formlimpic.journal-repair-tail:false}") boolean repairTail) throws IOException {
        return new ReceiptStore(Path.of(path), Clock.systemUTC(), repairTail);
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
    private final ScheduledExecutorService responseDelay = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "submit-response-delay");
        t.setDaemon(true);
        return t;
    });

    private final ArchivedResults archive;

    // BCrypt 한 번에 수십 ms의 CPU를 쓴다. 오픈 직전 로그인이 몰려도 접수 처리할 코어와 Tomcat 스레드가 남도록
    // 동시에 해시하는 수와 기다리는 수를 제한하고, 넘치면 바로 503으로 돌려보낸다
    static final int PASSWORD_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    static final int PASSWORD_MAX_WAITING = 32;
    private final java.util.concurrent.Semaphore passwordPermits = new java.util.concurrent.Semaphore(PASSWORD_WORKERS, true);
    private final java.util.concurrent.atomic.AtomicInteger passwordRequests = new java.util.concurrent.atomic.AtomicInteger();

    private <T> T passwordWork(java.util.function.Supplier<T> work) {
        String busy = "로그인 요청이 몰려 있습니다. 잠시 후 다시 시도해주세요.";
        try {
            if (passwordRequests.incrementAndGet() > PASSWORD_WORKERS + PASSWORD_MAX_WAITING) throw new IllegalStateException(busy);
            if (!passwordPermits.tryAcquire(3, TimeUnit.SECONDS)) throw new IllegalStateException(busy);
            try {
                return work.get();
            } finally {
                passwordPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(busy);
        } finally {
            passwordRequests.decrementAndGet();
        }
    }

    WebApi(ReceiptStore store, PasswordEncoder encoder, DiscordWebhookService discordService) {
        this(store, encoder, discordService, null);
    }

    @Autowired
    WebApi(ReceiptStore store, PasswordEncoder encoder, DiscordWebhookService discordService, ArchivedResults archive) {
        this.store = store;
        this.encoder = encoder;
        this.discordService = discordService;
        this.archive = archive;
    }

    // Finished forms are archived after a day: their submissions live only in PostgreSQL
    private ArchivedResults archive() {
        if (archive == null) throw new IllegalStateException("보관된 결과를 불러올 수 없습니다.");
        return archive;
    }
    private ReceiptStore.Ranking rankingFor(String formId) {
        return store.isArchived(formId) ? archive().ranking(formId) : store.ranking(formId);
    }
    private ReceiptStore.Receipt mineFor(String formId, String owner) {
        return store.isArchived(formId) ? archive().mine(formId, owner) : store.mine(formId, owner);
    }

    record NewForm(String title, String content, Instant startsAt, Instant expiresAt, Boolean hasBubble, Boolean hasDelay) {}
    record Answer(String name, String birthDate, String phone, String bubble) {
        public Answer(String name, String phone) {
            this(name, "", phone, "");
        }
    }
    record Row(int rank, String name, String membershipCode, Instant receivedAt, boolean early) {}
    record RankingPage(int total, int offset, List<Row> rows, Row mine) {}
    record AuthRequest(String username, String password) {}
    record WebhookRequest(String webhookUrl) {}
    record UserProfile(String id, String username, String membershipCode, String discordWebhookUrl, String role) implements java.io.Serializable {
        public UserProfile(String id, String username, String membershipCode, String discordWebhookUrl) {
            this(id, username, membershipCode, discordWebhookUrl, "USER");
        }
    }
    record AdminToggleRequest(boolean adminOnly) {}

    @ModelAttribute void headers(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod()) && !"formlimpic".equals(request.getHeader("X-Formlimpic"))) throw new IllegalArgumentException("잘못된 요청입니다.");
    }

    private UserProfile current(HttpSession session) {
        if (session == null) return null;
        return (UserProfile) session.getAttribute("auth_user");
    }

    // HttpSession 파라미터는 세션이 없으면 새로 만들기 때문에, 로그인 확인만 하는 곳은 getSession(false)로 읽는다
    private UserProfile current(HttpServletRequest req) {
        return current(req.getSession(false));
    }

    private UserProfile requireUser(HttpServletRequest req) {
        UserProfile u = current(req);
        if (u == null) throw new IllegalArgumentException("로그인이 필요합니다.");
        return u;
    }

    private UserProfile requireAdmin(HttpServletRequest req) {
        UserProfile u = requireUser(req);
        if (!"ADMIN".equalsIgnoreCase(u.role())) {
            throw new IllegalArgumentException("관리자 권한이 필요합니다.");
        }
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
        if ("admin".equalsIgnoreCase(req.username().trim())) {
            throw new IllegalArgumentException("해당 아이디는 시스템 예약어로 사용할 수 없습니다.");
        }
        if (req.password() == null || req.password().length() < 6 || req.password().length() > 50) {
            throw new IllegalArgumentException("비밀번호는 6~50자 사이여야 합니다.");
        }
        ReceiptStore.User user = store.registerUser(req.username().trim(), passwordWork(() -> encoder.encode(req.password())));
        UserProfile profile = new UserProfile(user.id(), user.username(), user.membershipCode(), user.discordWebhookUrl(), user.role());
        session.setAttribute("auth_user", profile);
        return Map.of("user", profile);
    }

    @PostMapping("/auth/login")
    Object login(@RequestBody AuthRequest req, HttpSession session) {
        if (req.username() == null || req.password() == null) throw new IllegalArgumentException("아이디와 비밀번호를 입력해주세요.");
        ReceiptStore.User user = store.findUserByUsername(req.username().trim())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다."));
        if (!passwordWork(() -> encoder.matches(req.password(), user.passwordHash()))) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 일치하지 않습니다.");
        }
        UserProfile profile = new UserProfile(user.id(), user.username(), user.membershipCode(), user.discordWebhookUrl(), user.role());
        session.setAttribute("auth_user", profile);
        return Map.of("user", profile);
    }

    @PutMapping("/my/webhook")
    Object updateWebhook(@RequestBody WebhookRequest req, HttpServletRequest request) {
        UserProfile user = requireUser(request);
        ReceiptStore.User updated = store.updateWebhook(user.id(), req.webhookUrl());
        UserProfile newProfile = new UserProfile(updated.id(), updated.username(), updated.membershipCode(), updated.discordWebhookUrl(), updated.role());
        request.getSession().setAttribute("auth_user", newProfile);
        return Map.of("ok", true, "user", newProfile);
    }

    @DeleteMapping("/my/webhook")
    Object deleteWebhook(HttpServletRequest request) {
        UserProfile user = requireUser(request);
        ReceiptStore.User updated = store.updateWebhook(user.id(), "");
        UserProfile newProfile = new UserProfile(updated.id(), updated.username(), updated.membershipCode(), updated.discordWebhookUrl(), updated.role());
        request.getSession().setAttribute("auth_user", newProfile);
        return Map.of("ok", true, "user", newProfile);
    }

    @PostMapping("/my/webhook/test")
    Object testWebhook(@RequestBody(required = false) WebhookRequest req, HttpServletRequest request) {
        UserProfile user = requireUser(request);
        String url = (req != null && req.webhookUrl() != null && !req.webhookUrl().isBlank())
                ? req.webhookUrl().trim()
                : user.discordWebhookUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("디스코드 웹훅 URL을 먼저 입력해주세요.");
        }
        // 서버가 임의 주소로 요청을 보내지 않도록(SSRF) 저장할 때와 같은 디스코드 웹훅 주소만 허용
        if (!ReceiptStore.isDiscordWebhookUrl(url)) {
            throw new IllegalArgumentException("올바른 디스코드 웹훅 URL을 입력해주세요.");
        }
        discordService.sendTestNotification(url, user.username());
        return Map.of("ok", true, "message", "테스트 알림이 전송되었습니다. 디스코드 채널을 확인하세요!");
    }

    @PostMapping("/auth/logout")
    Object logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/auth/me")
    Object me(HttpServletRequest req) {
        UserProfile user = current(req);
        if (user == null) return Map.of("loggedIn", false);
        return Map.of("loggedIn", true, "user", user);
    }

    @GetMapping("/my/forms")
    Object myForms(HttpServletRequest req) {
        UserProfile user = requireUser(req);
        return Map.of("forms", store.myForms(user.id()));
    }

    @GetMapping("/my/submissions")
    Object mySubmissions(HttpServletRequest req) {
        UserProfile user = requireUser(req);
        List<ReceiptStore.SubmissionSummary> list = new ArrayList<>(store.mySubmissions(user.id()));
        if (archive != null) list.addAll(archive.mySubmissions(user.id()));
        list.sort((a, b) -> b.receipt().receivedAt().compareTo(a.receipt().receivedAt()));
        return Map.of("submissions", list);
    }

    @GetMapping("/forms") Object list(HttpServletRequest req, HttpServletResponse res) {
        owner(req, res);
        return Map.of("forms", store.forms(), "serverTime", store.now(), "adminOnlyFormCreation", store.isAdminOnlyFormCreation());
    }

    @PostMapping("/forms")
    Object create(@RequestBody NewForm f, HttpServletRequest req) {
        UserProfile user = requireUser(req);
        if (store.isAdminOnlyFormCreation() && !"ADMIN".equalsIgnoreCase(user.role())) {
            throw new IllegalArgumentException("현재 관리자만 폼림픽을 개설할 수 있습니다.");
        }
        boolean hasBubble = f.hasBubble() != null && f.hasBubble();
        boolean hasDelay = f.hasDelay() == null || f.hasDelay();
        return store.create(user.id(), f.title(), f.content(), f.startsAt(), f.expiresAt(), hasBubble, hasDelay);
    }

    @DeleteMapping("/forms/{id}")
    Object deleteForm(@PathVariable String id, HttpServletRequest req) {
        requireAdmin(req);
        store.deleteForm(id);
        return Map.of("ok", true, "deletedFormId", id);
    }

    @PutMapping("/admin/settings/form-creation")
    Object toggleFormCreation(@RequestBody AdminToggleRequest req, HttpServletRequest request) {
        requireAdmin(request);
        store.setAdminOnlyFormCreation(req.adminOnly());
        return Map.of("ok", true, "adminOnlyFormCreation", store.isAdminOnlyFormCreation());
    }

    @GetMapping("/admin/settings")
    Object getAdminSettings(HttpServletRequest req) {
        requireAdmin(req);
        return Map.of("adminOnlyFormCreation", store.isAdminOnlyFormCreation());
    }

    @GetMapping("/forms/{id}") Object detail(@PathVariable String id, HttpServletRequest req, HttpServletResponse res) {
        ReceiptStore.Form form = store.form(id);
        Map<String, Object> data = new HashMap<>(); data.put("form", form); data.put("serverTime", store.now()); data.put("mine", mineFor(id, owner(req, res)));
        data.put("resultsAt", ReceiptStore.resultsAt(form));
        return data;
    }

    @PostMapping("/forms/{id}/ticket")
    Object ticket(@PathVariable String id, HttpServletRequest req) {
        UserProfile user = requireUser(req);
        ReceiptStore.Ticket t = store.ticket(id, user.id());
        return Map.of("code", t.code());
    }

    @PostMapping("/forms/{id}/submissions")
    Object submit(@PathVariable String id, @RequestBody Answer a, HttpServletRequest req) {
        UserProfile user = requireUser(req);
        ReceiptStore.Form form = store.form(id);
        // 접수 시각 = 서버 도착 시각 + 시뮬레이션 지연. 순위는 이 시각으로 정해지므로
        // 서버가 붐벼서 처리 순서가 밀려도(실제 대기열) 결과가 흔들리지 않는다.
        Instant arrivedAt = req.getAttribute(ArrivalTimeFilter.ATTRIBUTE) instanceof Instant t ? t : store.now();
        int delay = form.hasDelay() ? simulatedDelayMs(Duration.between(form.startsAt(), arrivedAt).toMillis()) : 0;
        Instant admittedAt = arrivedAt.plusMillis(delay);
        ReceiptStore.Receipt receipt = store.submit(id, user.id(), a.name(), a.birthDate(), a.phone(), a.bubble(), admittedAt);

        long wait = Duration.between(store.now(), admittedAt).toMillis();
        if (wait <= 0) return receipt;
        // 응답만 접수 시각까지 늦춘다. Thread.sleep 과 달리 톰캣 스레드를 붙잡지 않는다.
        DeferredResult<ReceiptStore.Receipt> result = new DeferredResult<>(10_000L);
        responseDelay.schedule(() -> result.setResult(receipt), wait, TimeUnit.MILLISECONDS);
        return result;
    }

    /** 서버 도착 시점(시작 시각 기준 ms)에 따른 시뮬레이션 지연: 실제 폼림픽의 통신 지연 + 대기열을 흉내낸다. */
    static int simulatedDelayMs(long diffFromStart) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (diffFromStart < -400) {
            // 1단계: 59.60초 이전 (대기열 제로, 순수 통신 지연: 40 ~ 55ms)
            return 40 + random.nextInt(16);
        } else if (diffFromStart < -200) {
            // 2단계: 59.60초 ~ 59.80초 (대기열 극소: 45 ~ 65ms)
            return 45 + random.nextInt(21);
        } else if (diffFromStart < -80) {
            // 3단계: 59.80초 ~ 59.92초 (선입력 유입 시작: 60 ~ 90ms)
            return 60 + random.nextInt(31);
        } else if (diffFromStart < 0) {
            // 4단계: 59.92초 ~ 정각 직전 (선입력 격전, 적체 시작: 80 ~ 120ms)
            return 80 + random.nextInt(41);
        } else if (diffFromStart < 100) {
            // 5단계: 정각 00.00초 ~ 00.10초 (정각단 유입, 큐 폭발: 130 ~ 200ms)
            return 130 + random.nextInt(71);
        } else if (diffFromStart < 1500) {
            // 6단계: 00.10초 ~ 01.50초 (트래픽 정점, 큐 최고조 포화: 200 ~ 320ms)
            return 200 + random.nextInt(121);
        } else if (diffFromStart < 3000) {
            // 7단계: 01.50초 ~ 03.00초 (대기열 1차 소진 중: 130 ~ 200ms)
            return 130 + random.nextInt(71);
        } else if (diffFromStart < 5000) {
            // 8단계: 03.00초 ~ 05.00초 (대기열 막바지 해소 중: 80 ~ 130ms)
            return 80 + random.nextInt(51);
        } else {
            // 9단계: 05.00초 이후 (완전 정상화 복귀: 50 ~ 70ms)
            return 50 + random.nextInt(21);
        }
    }

    @PreDestroy
    void stopResponseDelay() {
        responseDelay.shutdown(); // already scheduled responses still run (default executor policy)
    }

    static String maskName(String s) {
        if (s == null) return "";
        String str = s.trim();
        if (str.contains("*")) return str;
        int len = str.length();
        if (len <= 1) return "*";
        if (len == 2) return str.charAt(0) + "*";
        if (len == 3) return str.charAt(0) + "*" + str.charAt(2);
        if (len == 4) return str.charAt(0) + "**" + str.charAt(3);
        if (len == 5) return str.charAt(0) + "***" + str.charAt(4);
        int rem = len - 3;
        int front = (rem + 1) / 2;
        int back = rem / 2;
        return str.substring(0, front) + "***" + str.substring(len - back);
    }

    private static Row row(int rank, ReceiptStore.Receipt r) {
        return new Row(rank, maskName(r.name()), r.code(), r.receivedAt(), r.early());
    }

    /** Full list (kept for pages that still run the previous app.js). */
    @GetMapping("/forms/{id}/results") Object results(@PathVariable String id) {
        List<ReceiptStore.Receipt> ranked = rankingFor(id).receipts();
        List<Row> rows = new ArrayList<>(ranked.size());
        for (ReceiptStore.Receipt r : ranked) rows.add(row(rows.size() + 1, r));
        return rows;
    }

    /** One page of the final ranking plus the caller's own row, so every open page does not pull all rows at once. */
    @GetMapping("/forms/{id}/ranking")
    Object ranking(@PathVariable String id, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit, HttpServletRequest req) {
        ReceiptStore.Ranking ranking = rankingFor(id);
        List<ReceiptStore.Receipt> ranked = ranking.receipts();
        int from = Math.max(0, Math.min(offset, ranked.size()));
        int to = Math.min(ranked.size(), from + Math.max(1, Math.min(limit, 500)));
        List<Row> rows = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) rows.add(row(i + 1, ranked.get(i)));
        Row mine = null;
        UserProfile user = current(req);
        ReceiptStore.Receipt receipt = user != null ? mineFor(id, user.id()) : null;
        Integer rank = receipt != null ? ranking.rankByTicket().get(receipt.ticketId()) : null;
        if (rank != null) mine = row(rank, ranked.get(rank - 1));
        return new RankingPage(ranked.size(), from, rows, mine);
    }

    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> archiveUnavailable(org.springframework.dao.DataAccessException e) {
        return ResponseEntity.status(503).body(Map.of("message", "보관된 결과를 불러오지 못했습니다. 잠시 후 다시 시도하세요."));
    }
}
