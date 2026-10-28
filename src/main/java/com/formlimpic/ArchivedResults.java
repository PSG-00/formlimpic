package com.formlimpic;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Results, own answers and ranks of archived forms, read from the PostgreSQL projection.
 * Archived forms are finished and immutable, so a few recently viewed rankings are kept.
 */
@Component
public class ArchivedResults {
    private static final String COLUMNS = "s.id, s.form_id, s.ticket_id, s.name, s.birth_date, s.phone, s.bubble, s.code, s.received_at, s.admission_sequence, s.early";
    private static final String RANK_ORDER = "s.early, s.received_at, s.admission_sequence"; // same order as ReceiptStore.ranking
    private final JdbcTemplate jdbc;
    private final ReceiptStore store;
    private final Map<String, ReceiptStore.Ranking> recent = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ReceiptStore.Ranking> eldest) { return size() > 8; }
    });

    ArchivedResults(JdbcTemplate jdbc, ReceiptStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    private static ReceiptStore.Receipt receipt(ResultSet rs) throws SQLException {
        return new ReceiptStore.Receipt(rs.getString("id"), rs.getString("form_id"), rs.getString("ticket_id"), rs.getString("name"),
                Objects.toString(rs.getString("birth_date"), ""), rs.getString("phone"), Objects.toString(rs.getString("bubble"), ""),
                rs.getString("code").trim(), rs.getTimestamp("received_at").toInstant(), rs.getLong("admission_sequence"), rs.getBoolean("early"));
    }

    private static UUID uuid(String id) {
        try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
    }

    public ReceiptStore.Ranking ranking(String formId) {
        ReceiptStore.Ranking cached = recent.get(formId);
        if (cached != null) return cached;
        List<ReceiptStore.Receipt> ordered = jdbc.query("SELECT " + COLUMNS + " FROM submissions s WHERE s.form_id = ? ORDER BY " + RANK_ORDER,
                (rs, i) -> receipt(rs), uuid(formId));
        Map<String, Integer> rankByTicket = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) rankByTicket.put(ordered.get(i).ticketId(), i + 1);
        ReceiptStore.Ranking ranking = new ReceiptStore.Ranking(List.copyOf(ordered), Map.copyOf(rankByTicket));
        recent.put(formId, ranking);
        return ranking;
    }

    public ReceiptStore.Receipt mine(String formId, String owner) {
        UUID ownerId = owner != null ? uuid(owner) : null;
        if (ownerId == null) return null;
        List<ReceiptStore.Receipt> rows = jdbc.query("SELECT " + COLUMNS + " FROM submissions s JOIN memberships m ON m.id = s.ticket_id WHERE m.form_id = ? AND m.owner_id = ?",
                (rs, i) -> receipt(rs), uuid(formId), ownerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The owner's submissions to archived forms, with their final rank. */
    public List<ReceiptStore.SubmissionSummary> mySubmissions(String owner) {
        UUID ownerId = owner != null ? uuid(owner) : null;
        if (ownerId == null) return List.of();
        List<ReceiptStore.SubmissionSummary> list = new ArrayList<>();
        jdbc.query("SELECT * FROM (SELECT " + COLUMNS + ", m.owner_id, ROW_NUMBER() OVER (PARTITION BY s.form_id ORDER BY " + RANK_ORDER + ") AS place"
                        + " FROM submissions s JOIN memberships m ON m.id = s.ticket_id"
                        + " WHERE s.form_id IN (SELECT form_id FROM memberships WHERE owner_id = ?)) r WHERE r.owner_id = ?",
                rs -> {
                    ReceiptStore.Receipt r = receipt(rs);
                    if (!store.isArchived(r.formId())) return; // still in memory: listed by the store itself
                    ReceiptStore.Form f;
                    try { f = store.form(r.formId()); } catch (IllegalArgumentException deleted) { return; }
                    list.add(new ReceiptStore.SubmissionSummary(f, new ReceiptStore.Ticket(r.ticketId(), r.formId(), owner, r.code()), r, rs.getInt("place")));
                }, ownerId, ownerId);
        return list;
    }
}
