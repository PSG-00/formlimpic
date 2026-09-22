package com.formlimpic;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Single-process durable admission journal. Never acknowledge before force(true). */
public final class ReceiptStore implements AutoCloseable {
    public record User(String id, String username, String passwordHash, String membershipCode, String discordWebhookUrl, Instant createdAt) {}
    public record Form(String id, String owner, String title, String content, Instant startsAt, Instant expiresAt, boolean hasBubble) {
        public Form(String id, String owner, String title, String content, Instant startsAt, Instant expiresAt) {
            this(id, owner, title, content, startsAt, expiresAt, false);
        }
        public Form(String id, String title, String content, Instant startsAt, Instant expiresAt) {
            this(id, "", title, content, startsAt, expiresAt, false);
        }
    }
    public record Ticket(String id, String formId, String owner, String code) {}
    public record Receipt(String id, String formId, String ticketId, String name, String birthDate, String phone, String bubble,
                          String code, Instant receivedAt, long sequence, boolean early) {
        public Receipt(String id, String formId, String ticketId, String name, String phone,
                       String code, Instant receivedAt, long sequence, boolean early) {
            this(id, formId, ticketId, name, "", phone, "", code, receivedAt, sequence, early);
        }
    }
    public record SubmissionSummary(Form form, Ticket ticket, Receipt receipt, int rank) {}
    public record Event(long sequence, Properties values) {}
    private final FileChannel channel;
    private final FileLock lock;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, User> usersById = new HashMap<>();
    private final Map<String, User> usersByUsername = new HashMap<>();
    private final Map<String, User> usersByMembershipCode = new HashMap<>();
    private final Set<String> notifiedFormIds = new HashSet<>();
    private final Map<String, Form> forms = new LinkedHashMap<>();
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final Map<String, Ticket> ticketsByFormAndOwner = new HashMap<>();
    private final Map<String, Receipt> receipts = new HashMap<>();
    private static final Event POISON_PILL = new Event(-1L, new Properties());
    private final BlockingQueue<Event> journalQueue = new LinkedBlockingQueue<>();
    private final Thread flusherThread;
    private volatile boolean running = true;
    private boolean failed;

    public ReceiptStore(Path path, Clock clock) throws IOException {
        this.clock = clock;
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) { channel.close(); throw new IOException("Journal already in use"); }
        try { recover(); } catch (Exception e) { close(); throw e; }
        flusherThread = new Thread(this::flusherLoop, "journal-flusher");
        flusherThread.setDaemon(true);
        flusherThread.start();
    }
    private void recover() throws IOException {
        long good = 0;
        while (channel.position() < channel.size()) {
            if (channel.size() - channel.position() < 8) break;
            ByteBuffer header = ByteBuffer.allocate(8); readFully(header); header.flip();
            int length = header.getInt(), checksum = header.getInt();
            if (length < 1 || length > 1_000_000) throw new IOException("Invalid journal frame");
            if (channel.size() - channel.position() < length) break;
            ByteBuffer body = ByteBuffer.allocate(length); readFully(body);
            CRC32 crc = new CRC32(); crc.update(body.array());
            if ((int) crc.getValue() != checksum) throw new IOException("Journal checksum mismatch; restore from backup");
            Properties p = new Properties(); p.load(new ByteArrayInputStream(body.array()));
            Event event = new Event(events.size() + 1L, p); apply(event); events.add(event);
            good = channel.position();
        }
        channel.truncate(good); channel.position(good); channel.force(true);
    }
    private void readFully(ByteBuffer b) throws IOException { while (b.hasRemaining()) if (channel.read(b) < 0) throw new EOFException(); }
    private void append(Properties p) {
        if (failed) throw new IllegalStateException("접수 기록 장치에 문제가 있습니다. 잠시 후 다시 시도하세요.");
        Event event = new Event(events.size() + 1L, p);
        apply(event);
        events.add(event);
        journalQueue.offer(event);
    }
    private void flusherLoop() {
        List<Event> batch = new ArrayList<>(1024);
        while (running || !journalQueue.isEmpty()) {
            try {
                Event first = journalQueue.poll(20, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                if (first == POISON_PILL) break;
                batch.add(first);
                journalQueue.drainTo(batch, 1023);

                boolean poison = false;
                Iterator<Event> it = batch.iterator();
                while (it.hasNext()) {
                    if (it.next() == POISON_PILL) {
                        poison = true;
                        it.remove();
                    }
                }

                if (!batch.isEmpty()) {
                    flushBatch(batch);
                    batch.clear();
                }
                if (poison) break;
            } catch (InterruptedException ignored) {
                break;
            } catch (IOException e) {
                failed = true;
                break;
            }
        }
        List<Event> remaining = new ArrayList<>();
        journalQueue.drainTo(remaining);
        remaining.removeIf(b -> b == POISON_PILL);
        if (!remaining.isEmpty()) {
            try {
                flushBatch(remaining);
            } catch (IOException e) {
                failed = true;
            }
        }
    }
    private void flushBatch(List<Event> batch) throws IOException {
        synchronized (channel) {
            if (channel.isOpen()) {
                for (Event event : batch) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    event.values().store(out, null);
                    byte[] bytes = out.toByteArray();
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    ByteBuffer frame = ByteBuffer.allocate(8 + bytes.length)
                            .putInt(bytes.length)
                            .putInt((int) crc.getValue())
                            .put(bytes);
                    frame.flip();
                    while (frame.hasRemaining()) channel.write(frame);
                }
                channel.force(true);
            }
        }
    }
    private static Properties props(String... pairs) {
        Properties p = new Properties(); for (int i = 0; i < pairs.length; i += 2) p.setProperty(pairs[i], pairs[i + 1]); return p;
    }
    private void apply(Event e) {
        Properties p = e.values(); String id = p.getProperty("id");
        switch (p.getProperty("type")) {
            case "user" -> {
                String code = p.getProperty("code", "");
                String webhook = p.getProperty("webhook", "");
                User u = new User(id, p.getProperty("username"), p.getProperty("hash"), code, webhook, Instant.parse(p.getProperty("time")));
                usersById.put(u.id(), u);
                usersByUsername.put(u.username(), u);
                if (!code.isBlank()) usersByMembershipCode.put(code, u);
            }
            case "user_webhook" -> {
                String userId = p.getProperty("userId");
                String webhook = p.getProperty("webhook", "");
                User old = usersById.get(userId);
                if (old != null) {
                    User u = new User(old.id(), old.username(), old.passwordHash(), old.membershipCode(), webhook, old.createdAt());
                    usersById.put(u.id(), u);
                    usersByUsername.put(u.username(), u);
                    if (!u.membershipCode().isBlank()) usersByMembershipCode.put(u.membershipCode(), u);
                }
            }
            case "form_notified" -> notifiedFormIds.add(id);
            case "form" -> forms.put(id, new Form(id, p.getProperty("owner", ""), p.getProperty("title"), p.getProperty("content"), Instant.parse(p.getProperty("start")), Instant.parse(p.getProperty("end")), Boolean.parseBoolean(p.getProperty("hasBubble", "false"))));
            case "ticket" -> {
                Ticket t = new Ticket(id, p.getProperty("form"), p.getProperty("owner"), p.getProperty("code"));
                tickets.put(id, t);
                ticketsByFormAndOwner.put(t.formId() + "\0" + t.owner(), t);
            }
            case "receipt" -> receipts.put(p.getProperty("ticket"), new Receipt(id, p.getProperty("form"), p.getProperty("ticket"), p.getProperty("name"), p.getProperty("birthDate", ""), p.getProperty("phone"), p.getProperty("bubble", ""), p.getProperty("code"), Instant.parse(p.getProperty("time")), e.sequence(), Boolean.parseBoolean(p.getProperty("early"))));
            default -> throw new IllegalStateException("Unknown journal event");
        }
    }
    public synchronized User registerUser(String username, String passwordHash) {
        username = text(username, 30);
        if (usersByUsername.containsKey(username)) throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
        if (usersByMembershipCode.size() >= 308915776) throw new IllegalStateException("발급 가능한 멤버십 코드가 소진되었습니다.");
        String code;
        do {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append((char) ('A' + random.nextInt(26)));
            code = b.toString();
        } while (usersByMembershipCode.containsKey(code));
        String id = UUID.randomUUID().toString();
        Instant time = now();
        append(props("type", "user", "id", id, "username", username, "hash", passwordHash, "code", code, "time", time.toString()));
        return usersById.get(id);
    }
    public synchronized Optional<User> findUserByUsername(String username) {
        if (username == null) return Optional.empty();
        return Optional.ofNullable(usersByUsername.get(username));
    }
    public synchronized Optional<User> findUserById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(usersById.get(id));
    }
    public synchronized List<Form> forms() { return new ArrayList<>(forms.values()); }
    public synchronized Form form(String id) { Form f = forms.get(id); if (f == null) throw new IllegalArgumentException("폼림픽을 찾을 수 없습니다."); return f; }
    public Instant now() { return clock.instant(); }
    public synchronized Form create(String title, String content, Instant start, Instant end) {
        return create("", title, content, start, end, false);
    }
    public synchronized Form create(String owner, String title, String content, Instant start, Instant end) {
        return create(owner, title, content, start, end, false);
    }
    public synchronized Form create(String owner, String title, String content, Instant start, Instant end, boolean hasBubble) {
        title = text(title, 120); content = text(content, 10000);
        if (start == null || end == null || !start.isAfter(now()) || !end.isAfter(start)) throw new IllegalArgumentException("시작은 현재 이후, 만료는 시작 이후여야 합니다.");
        String id = UUID.randomUUID().toString();
        append(props("type", "form", "id", id, "owner", owner != null ? owner : "", "title", title, "content", content, "start", start.toString(), "end", end.toString(), "hasBubble", Boolean.toString(hasBubble)));
        return forms.get(id);
    }
    private void open(Form f, Instant time) {
        if (time.isBefore(f.startsAt().minusSeconds(600))) throw new IllegalArgumentException("신청 시작 10분 전부터 작성할 수 있습니다.");
        if (!time.isBefore(f.expiresAt())) throw new IllegalArgumentException("신청이 마감되었습니다.");
    }
    public synchronized Ticket ticket(String formId, String owner) {
        Form f = form(formId);
        Ticket existing = ticketsByFormAndOwner.get(formId + "\0" + owner);
        if (existing != null) return existing;
        open(f, now());
        String code;
        User user = usersById.get(owner);
        if (user != null && user.membershipCode() != null && !user.membershipCode().isBlank()) {
            code = user.membershipCode();
        } else {
            Set<String> used = new HashSet<>();
            for (Ticket t : tickets.values()) if (t.formId().equals(formId)) used.add(t.code());
            if (used.size() >= 308915776) throw new IllegalStateException("발급 가능한 코드가 소진되었습니다.");
            do { StringBuilder b = new StringBuilder(); for (int i = 0; i < 6; i++) b.append((char) ('A' + random.nextInt(26))); code = b.toString(); } while (used.contains(code));
        }
        String id = UUID.randomUUID().toString();
        append(props("type", "ticket", "id", id, "form", formId, "owner", owner, "code", code)); return tickets.get(id);
    }
    public synchronized Receipt mine(String formId, String owner) {
        Ticket t = ticketsByFormAndOwner.get(formId + "\0" + owner);
        if (t == null) return null;
        return receipts.get(t.id());
    }
    public synchronized Receipt submit(String formId, String owner, String name, String phone) {
        return submit(formId, owner, name, "", phone, "");
    }
    public synchronized Receipt submit(String formId, String owner, String name, String birthDate, String phone, String bubble) {
        Ticket t = ticketsByFormAndOwner.get(formId + "\0" + owner);
        if (t == null) throw new IllegalArgumentException("먼저 멤버십 코드를 발급받으세요.");
        Receipt existing = receipts.get(t.id());
        if (existing != null) return existing;
        name = text(name, 40);
        birthDate = optionalText(birthDate, 30);
        phone = text(phone, 30);
        bubble = optionalText(bubble, 50);
        Form f = form(formId);
        Instant time = now(); open(f, time);
        append(props("type", "receipt", "id", UUID.randomUUID().toString(), "form", formId, "ticket", t.id(), "name", name, "birthDate", birthDate, "phone", phone, "bubble", bubble, "code", t.code(), "time", time.toString(), "early", Boolean.toString(time.isBefore(f.startsAt()))));
        return receipts.get(t.id());
    }
    public synchronized List<Receipt> results(String formId) {
        if (now().isBefore(form(formId).expiresAt())) throw new IllegalArgumentException("만료 후 결과가 공개됩니다.");
        return receipts.values().stream().filter(r -> r.formId().equals(formId)).sorted(Comparator.comparing(Receipt::early).thenComparingLong(Receipt::sequence)).toList();
    }
    public synchronized List<Form> myForms(String owner) {
        if (owner == null || owner.isBlank()) return List.of();
        List<Form> list = new ArrayList<>();
        for (Form f : forms.values()) if (owner.equals(f.owner())) list.add(f);
        Collections.reverse(list);
        return list;
    }
    public synchronized List<SubmissionSummary> mySubmissions(String owner) {
        if (owner == null || owner.isBlank()) return List.of();
        List<SubmissionSummary> list = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            if (owner.equals(t.owner())) {
                Receipt r = receipts.get(t.id());
                if (r != null) {
                    Form f = forms.get(t.formId());
                    int rank = 0;
                    if (f != null && !now().isBefore(f.expiresAt())) {
                        List<Receipt> res = results(f.id());
                        for (int i = 0; i < res.size(); i++) {
                            if (res.get(i).ticketId().equals(t.id())) { rank = i + 1; break; }
                        }
                    }
                    list.add(new SubmissionSummary(f, t, r, rank));
                }
            }
        }
        list.sort((a, b) -> b.receipt().receivedAt().compareTo(a.receipt().receivedAt()));
        return list;
    }
    public synchronized User updateWebhook(String userId, String webhookUrl) {
        User old = usersById.get(userId);
        if (old == null) throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        String cleaned = webhookUrl != null ? webhookUrl.trim() : "";
        if (!cleaned.isBlank() && !cleaned.startsWith("https://discord.com/api/webhooks/") && !cleaned.startsWith("https://discordapp.com/api/webhooks/")) {
            throw new IllegalArgumentException("올바른 디스코드 웹훅 URL을 입력해주세요.");
        }
        append(props("type", "user_webhook", "id", UUID.randomUUID().toString(), "userId", userId, "webhook", cleaned));
        return usersById.get(userId);
    }
    public synchronized List<Form> unnotifiedExpiredForms() {
        Instant current = now();
        List<Form> list = new ArrayList<>();
        for (Form f : forms.values()) {
            if (!current.isBefore(f.expiresAt()) && !notifiedFormIds.contains(f.id())) {
                list.add(f);
            }
        }
        return list;
    }
    public synchronized void markFormNotified(String formId) {
        if (!notifiedFormIds.contains(formId)) {
            append(props("type", "form_notified", "id", formId));
        }
    }
    public synchronized Optional<Ticket> ticketById(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }
    public synchronized List<Event> eventsAfter(long sequence) { return events.stream().filter(e -> e.sequence() > sequence).limit(100).toList(); }
    private static String text(String value, int max) { if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("필수 항목과 입력 길이를 확인하세요."); return value.trim(); }
    private static String optionalText(String value, int max) { if (value == null || value.isBlank()) return ""; if (value.length() > max) throw new IllegalArgumentException("입력 길이를 확인하세요."); return value.trim(); }
    @Override
    public synchronized void close() throws IOException {
        running = false;
        journalQueue.offer(POISON_PILL);
        if (flusherThread != null && flusherThread.isAlive()) {
            try { flusherThread.join(5000); } catch (InterruptedException ignored) {}
        }
        List<Event> remaining = new ArrayList<>();
        journalQueue.drainTo(remaining);
        remaining.removeIf(b -> b == POISON_PILL);
        if (!remaining.isEmpty()) {
            flushBatch(remaining);
        }
        if (lock != null && lock.isValid()) lock.release();
        if (channel != null && channel.isOpen()) channel.close();
    }
}
