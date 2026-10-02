package com.dijo.benefits;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayerBenefitsService {
    private final JdbcTemplate jdbc;

    public PlayerBenefitsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Event(String id, String name, long feePaise,
                        int couponCount, String couponDescription) {}
    public record Coupon(String code, String description, String redeemedAt) {}
    public record Benefits(String paymentId, String eventId, String playerUsername,
                           String paymentReference, long amountPaise,
                           String confirmedAt, String kitHandedOverAt,
                           List<Coupon> coupons) {}

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public String createEvent(String name, long feePaise, int couponCount,
                              String couponDescription) {
        requireText(name, "Event name");
        requireText(couponDescription, "Coupon description");
        if (feePaise <= 0 || couponCount < 0 || couponCount > 100) {
            throw new IllegalArgumentException("Invalid fee or coupon count");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO benefit_events
                (id, name, fee_paise, coupon_count, coupon_description)
                VALUES (?, ?, ?, ?, ?)
                """, id, name, feePaise, couponCount, couponDescription);
        return id;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL', 'USER')")
    public List<Event> events() {
        return jdbc.query("SELECT * FROM benefit_events ORDER BY name",
                (rs, row) -> new Event(rs.getString("id"), rs.getString("name"),
                        rs.getLong("fee_paise"), rs.getInt("coupon_count"),
                        rs.getString("coupon_description")));
    }

    /** Record an independently verified full payment; not a gateway verifier. */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL')")
    public Benefits confirmPayment(String eventId, String playerUsername,
                                   String paymentReference, long amountPaise) {
        requireText(eventId, "Event ID");
        requireText(playerUsername, "Player username");
        requireText(paymentReference, "Payment reference");
        if (amountPaise <= 0) throw new IllegalArgumentException("Invalid amount");

        // The first DB operation is a write, avoiding a deferred read-to-write
        // upgrade when competing SQLite writers confirm payments.
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO entry_payments
                (id, event_id, player_username, payment_reference, amount_paise,
                 confirmed_by, confirmed_at)
                SELECT ?, e.id, u.username, ?, ?, ?, ?
                FROM benefit_events e JOIN users u ON u.username = ?
                WHERE e.id = ? AND e.fee_paise = ? AND u.enabled = 1
                AND EXISTS (SELECT 1 FROM authorities a
                            WHERE a.username = u.username AND a.authority = 'ROLE_USER')
                ON CONFLICT DO NOTHING
                """, id, paymentReference, amountPaise, actor(), Instant.now().toString(),
                playerUsername, eventId, amountPaise);

        if (inserted == 0) {
            var previous = jdbc.query("""
                    SELECT id FROM entry_payments WHERE event_id = ?
                    AND player_username = ? AND payment_reference = ? AND amount_paise = ?
                    """, (rs, row) -> rs.getString("id"),
                    eventId, playerUsername, paymentReference, amountPaise);
            if (previous.size() == 1) return load(previous.getFirst());
            throw new IllegalArgumentException(
                    "Invalid event, enabled player, fee, or conflicting payment reference");
        }

        Event event = jdbc.queryForObject("SELECT * FROM benefit_events WHERE id = ?",
                (rs, row) -> new Event(rs.getString("id"), rs.getString("name"),
                        rs.getLong("fee_paise"), rs.getInt("coupon_count"),
                        rs.getString("coupon_description")), eventId);
        jdbc.update("INSERT INTO welcome_kits(payment_id) VALUES (?)", id);
        for (int i = 0; i < event.couponCount(); i++) {
            jdbc.update("""
                    INSERT INTO player_coupons(code, payment_id, description)
                    VALUES (?, ?, ?)
                    """, UUID.randomUUID().toString(), id, event.couponDescription());
        }
        return load(id);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public Benefits myBenefits(String eventId) {
        return load(paymentId(eventId, actor()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL')")
    public Benefits playerBenefits(String eventId, String playerUsername) {
        return load(paymentId(eventId, playerUsername));
    }

    /** true = handed over now; false = already handed over. */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL')")
    public boolean handOverKit(String eventId, String playerUsername) {
        int changed = jdbc.update("""
                UPDATE welcome_kits SET handed_over_at = ?, handed_over_by = ?
                WHERE handed_over_at IS NULL AND payment_id IN
                (SELECT id FROM entry_payments WHERE event_id = ? AND player_username = ?)
                """, Instant.now().toString(), actor(), eventId, playerUsername);
        if (changed == 1) return true;
        paymentId(eventId, playerUsername); // Reject unpaid/nonexistent registration.
        return false;
    }

    /** Check event and player ownership before consuming the coupon. */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL')")
    public boolean redeemCoupon(String eventId, String playerUsername, String code) {
        requireText(code, "Coupon code");
        int changed = jdbc.update("""
                UPDATE player_coupons SET redeemed_at = ?, redeemed_by = ?
                WHERE code = ? AND redeemed_at IS NULL AND payment_id IN
                (SELECT id FROM entry_payments WHERE event_id = ? AND player_username = ?)
                """, Instant.now().toString(), actor(), code, eventId, playerUsername);
        if (changed == 1) return true;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM player_coupons c JOIN entry_payments p ON p.id = c.payment_id
                WHERE c.code = ? AND p.event_id = ? AND p.player_username = ?
                """, Integer.class, code, eventId, playerUsername);
        if (count == null || count == 0) throw new IllegalArgumentException("Coupon not found for player/event");
        return false;
    }

    private String paymentId(String eventId, String playerUsername) {
        var ids = jdbc.query("SELECT id FROM entry_payments WHERE event_id = ? AND player_username = ?",
                (rs, row) -> rs.getString("id"), eventId, playerUsername);
        if (ids.isEmpty()) throw new IllegalArgumentException("No confirmed entry payment");
        return ids.getFirst();
    }

    public record PlayerBenefitRow(String username, boolean enabled, String paymentId,
            String paymentReference, long amountPaise, String kitHandedOverAt,
            int couponsIssued, int couponsRedeemed) {
        public boolean paid() { return paymentId != null; }
        public int couponsRemaining() { return couponsIssued - couponsRedeemed; }
    }

    /** Player accounts (ROLE_USER, excluding staff), paid or unpaid for the event. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<PlayerBenefitRow> listPlayerBenefits(String eventId) {
        requireText(eventId, "Event ID");
        return jdbc.query("""
                SELECT u.username, u.enabled, p.id AS payment_id,
                       p.payment_reference, COALESCE(p.amount_paise, 0) AS amount_paise,
                       k.handed_over_at,
                       COALESCE(c.issued, 0) AS coupons_issued,
                       COALESCE(c.redeemed, 0) AS coupons_redeemed
                FROM users u
                LEFT JOIN entry_payments p ON p.player_username = u.username AND p.event_id = ?
                LEFT JOIN welcome_kits k ON k.payment_id = p.id
                LEFT JOIN (
                    SELECT payment_id, COUNT(*) AS issued,
                           SUM(CASE WHEN redeemed_at IS NOT NULL THEN 1 ELSE 0 END) AS redeemed
                    FROM player_coupons GROUP BY payment_id
                ) c ON c.payment_id = p.id
                WHERE EXISTS (SELECT 1 FROM authorities a
                              WHERE a.username = u.username AND a.authority = 'ROLE_USER')
                AND NOT EXISTS (SELECT 1 FROM authorities staff WHERE staff.username = u.username
                                AND staff.authority IN ('ROLE_ADMIN', 'ROLE_OFFICIAL'))
                ORDER BY u.username
                """, (rs, row) -> new PlayerBenefitRow(rs.getString("username"),
                rs.getBoolean("enabled"), rs.getString("payment_id"),
                rs.getString("payment_reference"), rs.getLong("amount_paise"),
                rs.getString("handed_over_at"), rs.getInt("coupons_issued"),
                rs.getInt("coupons_redeemed")), eventId);
    }

    private Benefits load(String id) {
        List<Coupon> coupons = jdbc.query("SELECT * FROM player_coupons WHERE payment_id = ? ORDER BY code",
                (rs, row) -> new Coupon(rs.getString("code"), rs.getString("description"),
                        rs.getString("redeemed_at")), id);
        return jdbc.queryForObject("""
                SELECT p.*, k.handed_over_at FROM entry_payments p
                JOIN welcome_kits k ON k.payment_id = p.id WHERE p.id = ?
                """, (rs, row) -> new Benefits(rs.getString("id"), rs.getString("event_id"),
                rs.getString("player_username"), rs.getString("payment_reference"),
                rs.getLong("amount_paise"), rs.getString("confirmed_at"),
                rs.getString("handed_over_at"), coupons), id);
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Authentication required");
        }
        return authentication.getName();
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
