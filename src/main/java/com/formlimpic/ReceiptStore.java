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
import java.util.function.Supplier;
import java.util.zip.CRC32;

/** Single-process durable admission journal. Never acknowledge before force(true). */
public final class ReceiptStore implements AutoCloseable {
    public record User(String id, String username, String passwordHash, String membershipCode, String discordWebhookUrl, String role, Instant createdAt) {
        public User(String id, String username, String passwordHash, String membershipCode, String discordWebhookUrl, Instant createdAt) {
            this(id, username, passwordHash, membershipCode, discordWebhookUrl, "USER", createdAt);
        }
    }
    public record Form(String id, String owner, String title, String content, Instant startsAt, Instant expiresAt, boolean hasBubble, boolean adminCreated, boolean hasDelay) {
        public Form(String id, String owner, String title, String content, Instant startsAt, Instant expiresAt, boolean hasBubble, boolean adminCreated) {
            this(id, owner, title, content, startsAt, expiresAt, hasBubble, adminCreated, true);
        }
        public Form(String id, String owner, String title, String content, Instant startsAt, Instant expiresAt, boolean hasBubble) {
            this(id, owner, title, content, startsAt, expiresAt, hasBubble, false, true);
        }
        public Form(String id, String owner, String title, String content, Instant startsAt, Instant expiresAt) {
            this(id, owner, title, content, startsAt, expiresAt, false, false, true);
        }
        public Form(String id, String title, String content, Instant startsAt, Instant expiresAt) {
            this(id, "", title, content, startsAt, expiresAt, false, false, true);
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
    /** Final ranking of a form: receipts in rank order and 1-based rank by ticket id. Immutable. */
    public record Ranking(List<Receipt> receipts, Map<String, Integer> rankByTicket) {}
    /**
     * Results are final once no in-flight submission can still be admitted. A request that arrived before expiry
     * may be processed a little later under load, so submissions are accepted until expiry + this grace and
     * results open after it.
     */
    static final Duration RESULTS_FINALIZE_DELAY = Duration.ofSeconds(3);
    public record Event(long sequence, Properties values) {}
    private final FileChannel channel;
    private final FileLock lock;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    // Events are not kept in memory: only their frame offsets, so the DB projection can re-read them from the journal
    private long eventCount;
    private long[] offsets = new long[1024]; // offsets[i] = file position of event i + 1 (guarded by channel)
    private final Map<String, User> usersById = new HashMap<>();
    private final Map<String, User> usersByUsername = new HashMap<>();
    private final Map<String, User> usersByMembershipCode = new HashMap<>();
    private final Set<String> notifiedFormIds = new HashSet<>();
    private final Map<String, Form> forms = new LinkedHashMap<>();
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final Map<String, Ticket> ticketsByFormAndOwner = new HashMap<>();
    private final Map<String, Receipt> receipts = new HashMap<>();
    private final Map<String, List<Receipt>> receiptsByForm = new HashMap<>();
    private final Map<String, List<Ticket>> ticketsByOwner = new HashMap<>();
    private final Map<String, List<Ticket>> ticketsByForm = new HashMap<>();
    private final Map<String, Long> lastSequenceByForm = new HashMap<>(); // last ticket/receipt event of a form
    private final Set<String> archivedFormIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Ranking> rankings = new ConcurrentHashMap<>();
    private volatile boolean adminOnlyFormCreation = false;
    private static final Event POISON_PILL = new Event(-1L, new Properties());
    private final BlockingQueue<Event> journalQueue = new LinkedBlockingQueue<>();
    private final Thread flusherThread;
    private volatile boolean running = true;
    private volatile boolean failed; // set by journal-flusher, read by request threads
    private static final String FAILED_MESSAGE = "접수 기록 장치에 문제가 있습니다. 잠시 후 다시 시도하세요.";
    private static final long DURABLE_TIMEOUT_MS = 5000;
    private final Path path;
    private final boolean repairTail;
    private volatile long appendedSequence; // last event handed to the flusher
    private volatile long durableSequence;  // last event written and forced to disk
    private final Object durability = new Object();
    private volatile IOException injectedFailure; // tests only: simulate a failing journal device

    public ReceiptStore(Path path, Clock clock) throws IOException {
        this(path, clock, false);
    }
    /** repairTail: on a corrupt frame, back the file up and truncate there instead of refusing to start. */
    public ReceiptStore(Path path, Clock clock, boolean repairTail) throws IOException {
        this.clock = clock;
        this.path = path;
        this.repairTail = repairTail;
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) { channel.close(); throw new IOException("Journal already in use"); }
        try { recover(); } catch (Exception e) { close(); throw e; }
        appendedSequence = durableSequence = eventCount;
        flusherThread = new Thread(this::flusherLoop, "journal-flusher");
        flusherThread.setDaemon(true);
        flusherThread.start();
    }
    private static final byte[] ARCHIVED_MARKER = "type=form_archived".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    /** First pass: forms archived anywhere in the journal, so replay never loads their submissions. */
    private Set<String> scanArchivedForms() throws IOException {
        Set<String> ids = new HashSet<>();
        long pos = 0, size = channel.size();
        while (size - pos >= 8) {
            ByteBuffer header = ByteBuffer.allocate(8); readAt(header, pos); header.flip();
            int length = header.getInt();
            if (length < 1 || length > 1_000_000 || size - pos - 8 < length) break; // the real pass reports corruption
            ByteBuffer body = ByteBuffer.allocate(length); readAt(body, pos + 8);
            if (contains(body.array(), ARCHIVED_MARKER)) {
                Properties p = new Properties(); p.load(new ByteArrayInputStream(body.array()));
                if ("form_archived".equals(p.getProperty("type"))) ids.add(p.getProperty("id"));
            }
            pos += 8 + length;
        }
        return ids;
    }
    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
    private void recover() throws IOException {
        archivedFormIds.addAll(scanArchivedForms());
        channel.position(0);
        long good = 0;
        String corrupt = null;
        while (channel.position() < channel.size()) {
            if (channel.size() - channel.position() < 8) break;
            ByteBuffer header = ByteBuffer.allocate(8); readFully(header); header.flip();
            int length = header.getInt(), checksum = header.getInt();
            if (length < 1 || length > 1_000_000) { corrupt = "Invalid journal frame"; break; }
            if (channel.size() - channel.position() < length) break;
            ByteBuffer body = ByteBuffer.allocate(length); readFully(body);
            CRC32 crc = new CRC32(); crc.update(body.array());
            if ((int) crc.getValue() != checksum) { corrupt = "Journal checksum mismatch"; break; }
            Properties p = new Properties(); p.load(new ByteArrayInputStream(body.array()));
            addOffset(good); Event event = new Event(eventCount + 1L, p); apply(event); eventCount++;
            good = channel.position();
        }
        if (corrupt != null) {
            if (!repairTail) throw new IOException(corrupt + " at byte " + good + "; restore from backup, or start once with JOURNAL_REPAIR_TAIL=true to keep the " + eventCount + " events before it");
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try (FileChannel copy = FileChannel.open(backup, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.transferTo(0, channel.size(), copy);
                copy.force(true);
            }
            System.err.printf("[journal] %s at byte %d: kept %d events, dropped %d bytes, original saved to %s%n", corrupt, good, eventCount, channel.size() - good, backup);
        }
        channel.truncate(good); channel.position(good); channel.force(true);
    }
    private void readFully(ByteBuffer b) throws IOException { while (b.hasRemaining()) if (channel.read(b) < 0) throw new EOFException(); }
    private void readAt(ByteBuffer b, long position) throws IOException {
        while (b.hasRemaining()) if (channel.read(b, position + b.position()) < 0) throw new EOFException();
    }
    private void addOffset(long position) { // recovery, then the flusher under the channel lock
        if (eventCountOnDisk == offsets.length) offsets = Arrays.copyOf(offsets, offsets.length * 2);
        offsets[eventCountOnDisk++] = position;
    }
    private int eventCountOnDisk;
    private void append(Properties p) {
        if (failed) throw new IllegalStateException(FAILED_MESSAGE);
        Event event = new Event(++eventCount, p);
        apply(event);
        journalQueue.offer(event);
        appendedSequence = event.sequence();
    }
    public long durableSequence() { return durableSequence; }
    /** Durable events after the given sequence, re-read from the journal (for the DB projection). */
    public List<Event> durableEventsAfter(long sequence, int limit) throws IOException {
        long upTo = Math.min(durableSequence, sequence + limit);
        if (upTo <= sequence) return List.of();
        long[] positions;
        synchronized (channel) { positions = Arrays.copyOfRange(offsets, (int) sequence, (int) upTo); }
        // Positional reads do not move the channel position, so they run alongside the flusher's appends
        List<Event> out = new ArrayList<>(positions.length);
        for (int i = 0; i < positions.length; i++) {
            ByteBuffer header = ByteBuffer.allocate(8); readAt(header, positions[i]); header.flip();
            ByteBuffer body = ByteBuffer.allocate(header.getInt()); readAt(body, positions[i] + 8);
            Properties p = new Properties(); p.load(new ByteArrayInputStream(body.array()));
            out.add(new Event(sequence + i + 1, p));
        }
        return out;
    }
    /**
     * Runs a write under the store lock, then waits outside it until the journal is forced to disk, so a
     * success is never answered for a record a crash could lose. Concurrent writers share one fsync (group commit).
     * Waiting for everything appended so far also covers returning an existing record written by another request.
     */
    private <T> T durable(Supplier<T> write) {
        T result = write.get();
        awaitDurable(appendedSequence);
        return result;
    }
    private void awaitDurable(long target) {
        if (durableSequence >= target) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DURABLE_TIMEOUT_MS);
        synchronized (durability) {
            while (durableSequence < target) {
                if (failed) throw new IllegalStateException(FAILED_MESSAGE);
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) throw new IllegalStateException("접수 기록이 지연되고 있습니다. 잠시 후 다시 시도하세요.");
                try { durability.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(FAILED_MESSAGE); }
            }
        }
    }
    private void markDurable(long sequence) {
        synchronized (durability) { durableSequence = sequence; durability.notifyAll(); }
    }
    private void markFailed() {
        synchronized (durability) { failed = true; durability.notifyAll(); }
    }
    void simulateDiskFailureForTest() { injectedFailure = new IOException("simulated journal device failure"); }
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
                markFailed();
                break;
            }
        }
        // After a failed write the file may end in a partial frame: appending more would bury it mid-file
        // and make the journal unreadable, so nothing more is written.
        if (failed) return;
        List<Event> remaining = new ArrayList<>();
        journalQueue.drainTo(remaining);
        remaining.removeIf(b -> b == POISON_PILL);
        if (!remaining.isEmpty()) {
            try {
                flushBatch(remaining);
            } catch (IOException e) {
                markFailed();
            }
        }
    }
    private void flushBatch(List<Event> batch) throws IOException {
        synchronized (channel) {
            if (injectedFailure != null) throw injectedFailure;
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
                    addOffset(channel.position());
                    while (frame.hasRemaining()) channel.write(frame);
                }
                channel.force(true);
                markDurable(batch.get(batch.size() - 1).sequence());
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
                String username = p.getProperty("username");
                String defaultRole = "admin".equalsIgnoreCase(username) ? "ADMIN" : "USER";
                String role = p.getProperty("role", defaultRole);
                User u = new User(id, username, p.getProperty("hash"), code, webhook, role, Instant.parse(p.getProperty("time")));
                usersById.put(u.id(), u);
                usersByUsername.put(u.username(), u);
                if (!code.isBlank()) usersByMembershipCode.put(code, u);
            }
            case "user_webhook" -> {
                String userId = p.getProperty("userId");
                String webhook = p.getProperty("webhook", "");
                User old = usersById.get(userId);
                if (old != null) {
                    User u = new User(old.id(), old.username(), old.passwordHash(), old.membershipCode(), webhook, old.role(), old.createdAt());
                    usersById.put(u.id(), u);
                    usersByUsername.put(u.username(), u);
                    if (!u.membershipCode().isBlank()) usersByMembershipCode.put(u.membershipCode(), u);
                }
            }
            case "user_password" -> {
                String userId = p.getProperty("userId");
                String hash = p.getProperty("hash");
                User old = usersById.get(userId);
                if (old != null) {
                    User u = new User(old.id(), old.username(), hash, old.membershipCode(), old.discordWebhookUrl(), old.role(), old.createdAt());
                    usersById.put(u.id(), u);
                    usersByUsername.put(u.username(), u);
                    if (!u.membershipCode().isBlank()) usersByMembershipCode.put(u.membershipCode(), u);
                }
            }
            case "form_notified" -> notifiedFormIds.add(id);
            case "form" -> {
                String owner = p.getProperty("owner", "");
                boolean adminCreated = Boolean.parseBoolean(p.getProperty("adminCreated", "false"));
                if (!adminCreated && !owner.isBlank()) {
                    User u = usersById.get(owner);
                    if (u != null && "ADMIN".equalsIgnoreCase(u.role())) {
                        adminCreated = true;
                    }
                }
                boolean hasDelay = Boolean.parseBoolean(p.getProperty("hasDelay", "true"));
                forms.put(id, new Form(id, owner, p.getProperty("title"), p.getProperty("content"), Instant.parse(p.getProperty("start")), Instant.parse(p.getProperty("end")), Boolean.parseBoolean(p.getProperty("hasBubble", "false")), adminCreated, hasDelay));
            }
            case "form_deleted" -> {
                forms.remove(id);
                notifiedFormIds.remove(id);
                archivedFormIds.remove(id);
                evictSubmissions(id);
            }
            // Finished form whose submissions are in PostgreSQL: drop them from memory (results are read from the DB)
            case "form_archived" -> {
                archivedFormIds.add(id);
                evictSubmissions(id);
            }
            case "setting" -> {
                if ("adminOnlyFormCreation".equals(p.getProperty("key"))) {
                    adminOnlyFormCreation = Boolean.parseBoolean(p.getProperty("value"));
                }
            }
            case "ticket" -> {
                if (archivedFormIds.contains(p.getProperty("form"))) return; // replay: already archived later in the journal
                lastSequenceByForm.put(p.getProperty("form"), e.sequence());
                // Reuse the form/user strings already in memory instead of one parsed copy per ticket
                Form f = forms.get(p.getProperty("form"));
                User u = usersById.get(p.getProperty("owner"));
                String code = p.getProperty("code");
                Ticket t = new Ticket(id, f != null ? f.id() : p.getProperty("form"), u != null ? u.id() : p.getProperty("owner"),
                        u != null && code.equals(u.membershipCode()) ? u.membershipCode() : code);
                tickets.put(id, t);
                ticketsByFormAndOwner.put(t.formId() + "\0" + t.owner(), t);
                ticketsByOwner.computeIfAbsent(t.owner(), k -> new ArrayList<>()).add(t);
                ticketsByForm.computeIfAbsent(t.formId(), k -> new ArrayList<>()).add(t);
            }
            case "receipt" -> {
                if (archivedFormIds.contains(p.getProperty("form"))) return;
                lastSequenceByForm.put(p.getProperty("form"), e.sequence());
                Ticket t = tickets.get(p.getProperty("ticket"));
                Receipt r = new Receipt(id, t != null ? t.formId() : p.getProperty("form"), t != null ? t.id() : p.getProperty("ticket"), p.getProperty("name"), p.getProperty("birthDate", ""), p.getProperty("phone"), p.getProperty("bubble", ""), t != null ? t.code() : p.getProperty("code"), Instant.parse(p.getProperty("time")), e.sequence(), Boolean.parseBoolean(p.getProperty("early")));
                receipts.put(r.ticketId(), r);
                receiptsByForm.computeIfAbsent(r.formId(), k -> new ArrayList<>()).add(r);
            }
            default -> throw new IllegalStateException("Unknown journal event");
        }
    }
    private void evictSubmissions(String formId) {
        receiptsByForm.remove(formId);
        rankings.remove(formId);
        lastSequenceByForm.remove(formId);
        List<Ticket> formTickets = ticketsByForm.remove(formId);
        if (formTickets == null) return;
        for (Ticket t : formTickets) {
            tickets.remove(t.id());
            ticketsByFormAndOwner.remove(t.formId() + "\0" + t.owner());
            List<Ticket> owned = ticketsByOwner.get(t.owner());
            if (owned != null && owned.remove(t) && owned.isEmpty()) ticketsByOwner.remove(t.owner());
            receipts.remove(t.id());
        }
    }
    // Writes: public methods wait for the disk outside the lock; the *Locked variants do the in-memory work.
    public User registerUser(String username, String passwordHash) { return durable(() -> registerUserLocked(username, passwordHash)); }
    public void setAdminOnlyFormCreation(boolean adminOnly) { durable(() -> { setAdminOnlyFormCreationLocked(adminOnly); return null; }); }
    public void deleteForm(String formId) { durable(() -> { deleteFormLocked(formId); return null; }); }
    public User updateUserPassword(String userId, String passwordHash) { return durable(() -> updateUserPasswordLocked(userId, passwordHash)); }
    public Form create(String owner, String title, String content, Instant start, Instant end, boolean hasBubble, boolean hasDelay) {
        return durable(() -> createLocked(owner, title, content, start, end, hasBubble, hasDelay));
    }
    public Ticket ticket(String formId, String owner) { return durable(() -> ticketLocked(formId, owner)); }
    /** time: the admission time the receipt is ranked by (server arrival + simulated network delay). */
    public Receipt submit(String formId, String owner, String name, String birthDate, String phone, String bubble, Instant time) {
        return durable(() -> submitLocked(formId, owner, name, birthDate, phone, bubble, time));
    }
    public User updateWebhook(String userId, String webhookUrl) { return durable(() -> updateWebhookLocked(userId, webhookUrl)); }
    public void markFormNotified(String formId) { durable(() -> { markFormNotifiedLocked(formId); return null; }); }
    public void archiveForm(String formId) { durable(() -> { archiveFormLocked(formId); return null; }); }

    public boolean isArchived(String formId) { return archivedFormIds.contains(formId); }
    public record ArchiveCandidate(String formId, long lastSequence, int receipts) {}
    /** Notified forms still held in memory whose results were published before the cutoff. */
    public synchronized List<ArchiveCandidate> archiveCandidates(Instant resultsBefore) {
        List<ArchiveCandidate> list = new ArrayList<>();
        for (Form f : forms.values()) {
            if (!ticketsByForm.containsKey(f.id()) || !notifiedFormIds.contains(f.id()) || resultsAt(f).isAfter(resultsBefore)) continue;
            list.add(new ArchiveCandidate(f.id(), lastSequenceByForm.getOrDefault(f.id(), 0L), receiptsByForm.getOrDefault(f.id(), List.of()).size()));
        }
        return list;
    }
    private synchronized void archiveFormLocked(String formId) {
        Form f = form(formId);
        if (archivedFormIds.contains(formId)) return;
        if (now().isBefore(resultsAt(f))) throw new IllegalStateException("진행 중인 폼림픽은 보관할 수 없습니다.");
        append(props("type", "form_archived", "id", formId));
    }

    private synchronized User registerUserLocked(String username, String passwordHash) {
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
        String role = "admin".equalsIgnoreCase(username) ? "ADMIN" : "USER";
        append(props("type", "user", "id", id, "username", username, "hash", passwordHash, "code", code, "role", role, "time", time.toString()));
        return usersById.get(id);
    }
    public boolean isAdminOnlyFormCreation() { return adminOnlyFormCreation; }
    private synchronized void setAdminOnlyFormCreationLocked(boolean adminOnly) {
        append(props("type", "setting", "key", "adminOnlyFormCreation", "value", Boolean.toString(adminOnly)));
    }
    private synchronized void deleteFormLocked(String formId) {
        if (!forms.containsKey(formId)) throw new IllegalArgumentException("삭제할 폼림픽을 찾을 수 없습니다.");
        append(props("type", "form_deleted", "id", formId));
    }
    public synchronized boolean isOwnerAdmin(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return false;
        User u = usersById.get(ownerId);
        return u != null && "ADMIN".equalsIgnoreCase(u.role());
    }
    private synchronized User updateUserPasswordLocked(String userId, String passwordHash) {
        User u = usersById.get(userId);
        if (u == null) throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        append(props("type", "user_password", "id", UUID.randomUUID().toString(), "userId", userId, "hash", passwordHash, "time", now().toString()));
        return usersById.get(userId);
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
    public Form create(String title, String content, Instant start, Instant end) {
        return create("", title, content, start, end, false, true);
    }
    public Form create(String owner, String title, String content, Instant start, Instant end) {
        return create(owner, title, content, start, end, false, true);
    }
    public Form create(String owner, String title, String content, Instant start, Instant end, boolean hasBubble) {
        return create(owner, title, content, start, end, hasBubble, true);
    }
    private synchronized Form createLocked(String owner, String title, String content, Instant start, Instant end, boolean hasBubble, boolean hasDelay) {
        title = text(title, 120); content = text(content, 10000);
        if (start == null || end == null || !start.isAfter(now()) || !end.isAfter(start)) throw new IllegalArgumentException("시작은 현재 이후, 만료는 시작 이후여야 합니다.");
        String id = UUID.randomUUID().toString();
        boolean adminCreated = isOwnerAdmin(owner);
        append(props("type", "form", "id", id, "owner", owner != null ? owner : "", "title", title, "content", content, "start", start.toString(), "end", end.toString(), "hasBubble", Boolean.toString(hasBubble), "adminCreated", Boolean.toString(adminCreated), "hasDelay", Boolean.toString(hasDelay)));
        return forms.get(id);
    }
    private void open(Form f, Instant time) {
        if (time.isBefore(f.startsAt().minusSeconds(600))) throw new IllegalArgumentException("신청 시작 10분 전부터 작성할 수 있습니다.");
        if (!time.isBefore(f.expiresAt())) throw new IllegalArgumentException("신청이 마감되었습니다.");
    }
    private synchronized Ticket ticketLocked(String formId, String owner) {
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
    public Receipt submit(String formId, String owner, String name, String phone) {
        return submit(formId, owner, name, "", phone, "");
    }
    public Receipt submit(String formId, String owner, String name, String birthDate, String phone, String bubble) {
        return submit(formId, owner, name, birthDate, phone, bubble, now());
    }
    private synchronized Receipt submitLocked(String formId, String owner, String name, String birthDate, String phone, String bubble, Instant time) {
        Ticket t = ticketsByFormAndOwner.get(formId + "\0" + owner);
        if (t == null) throw new IllegalArgumentException("먼저 멤버십 코드를 발급받으세요.");
        Receipt existing = receipts.get(t.id());
        if (existing != null) return existing;
        name = text(name, 40);
        birthDate = optionalText(birthDate, 30);
        phone = text(phone, 30);
        bubble = optionalText(bubble, 50);
        Form f = form(formId);
        // Too late to be processed even if it arrived in time: results may already be published
        if (!now().isBefore(resultsAt(f))) throw new IllegalArgumentException("신청이 마감되었습니다.");
        open(f, time);
        append(props("type", "receipt", "id", UUID.randomUUID().toString(), "form", formId, "ticket", t.id(), "name", name, "birthDate", birthDate, "phone", phone, "bubble", bubble, "code", t.code(), "time", time.toString(), "early", Boolean.toString(time.isBefore(f.startsAt()))));
        return receipts.get(t.id());
    }
    public static Instant resultsAt(Form f) { return f.expiresAt().plus(RESULTS_FINALIZE_DELAY); }
    public List<Receipt> results(String formId) { return ranking(formId).receipts(); }
    /** Computed once per form after resultsAt (nothing can be admitted any more), then served without the store lock. */
    public Ranking ranking(String formId) {
        if (archivedFormIds.contains(formId)) throw new IllegalStateException("보관된 폼림픽은 데이터베이스에서 조회합니다.");
        Ranking cached = rankings.get(formId);
        if (cached != null) return cached;
        synchronized (this) {
            Form f = form(formId);
            Instant current = now();
            if (current.isBefore(f.expiresAt())) throw new IllegalArgumentException("만료 후 결과가 공개됩니다.");
            if (current.isBefore(resultsAt(f))) throw new IllegalArgumentException("결과를 집계하고 있습니다. 잠시 후 다시 확인하세요.");
            return rankings.computeIfAbsent(formId, id -> {
                // Admission time first (arrival + simulated delay), journal sequence breaks exact ties.
                // Older receipts were stamped under the same lock as their sequence, so their order is unchanged.
                List<Receipt> ordered = receiptsByForm.getOrDefault(id, List.of()).stream()
                        .sorted(Comparator.comparing(Receipt::early).thenComparing(Receipt::receivedAt).thenComparingLong(Receipt::sequence))
                        .toList();
                Map<String, Integer> rankByTicket = new HashMap<>();
                for (int i = 0; i < ordered.size(); i++) rankByTicket.put(ordered.get(i).ticketId(), i + 1);
                return new Ranking(ordered, Map.copyOf(rankByTicket));
            });
        }
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
        for (Ticket t : ticketsByOwner.getOrDefault(owner, List.of())) {
            Receipt r = receipts.get(t.id());
            if (r != null) {
                Form f = forms.get(t.formId());
                int rank = 0;
                if (f != null && !now().isBefore(resultsAt(f))) {
                    rank = ranking(f.id()).rankByTicket().getOrDefault(t.id(), 0);
                }
                list.add(new SubmissionSummary(f, t, r, rank));
            }
        }
        list.sort((a, b) -> b.receipt().receivedAt().compareTo(a.receipt().receivedAt()));
        return list;
    }
    private synchronized User updateWebhookLocked(String userId, String webhookUrl) {
        User old = usersById.get(userId);
        if (old == null) throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        String cleaned = webhookUrl != null ? webhookUrl.trim() : "";
        if (!cleaned.isBlank() && !isDiscordWebhookUrl(cleaned)) {
            throw new IllegalArgumentException("올바른 디스코드 웹훅 URL을 입력해주세요.");
        }
        append(props("type", "user_webhook", "id", UUID.randomUUID().toString(), "userId", userId, "webhook", cleaned));
        return usersById.get(userId);
    }
    public static boolean isDiscordWebhookUrl(String url) {
        return url != null && (url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/"));
    }
    public synchronized List<Form> unnotifiedExpiredForms() {
        Instant current = now();
        List<Form> list = new ArrayList<>();
        for (Form f : forms.values()) {
            if (!current.isBefore(resultsAt(f)) && !notifiedFormIds.contains(f.id())) {
                list.add(f);
            }
        }
        return list;
    }
    private synchronized void markFormNotifiedLocked(String formId) {
        if (!notifiedFormIds.contains(formId)) {
            append(props("type", "form_notified", "id", formId));
        }
    }
    public synchronized Optional<Ticket> ticketById(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }
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
        if (!remaining.isEmpty() && !failed) {
            flushBatch(remaining);
        }
        if (lock != null && lock.isValid()) lock.release();
        if (channel != null && channel.isOpen()) channel.close();
    }
}
