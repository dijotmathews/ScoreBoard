package com.dijo.events;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An event's draw: one slot per contestant. Signed-up players (confirmed entry
 * payment) take part in an event by being placed in a slot by an admin; a slot
 * with no opponent gets a BYE. A locked draw cannot change until it is unlocked.
 */
@Service
public class DrawService {
    /**
     * Creates slots 1..contestant_count for events that have no draw yet.
     * Bind the event ID twice to target one event, or null twice for all events.
     */
    public static final String CREATE_EMPTY_DRAWS = """
            WITH RECURSIVE n(slot) AS (
                SELECT 1 UNION ALL SELECT slot + 1 FROM n WHERE slot < %d)
            INSERT INTO draw_slots(event_id, slot)
            SELECT e.id, n.slot FROM events e JOIN n ON n.slot <= e.contestant_count
            WHERE (? IS NULL OR e.id = ?)
            AND NOT EXISTS (SELECT 1 FROM draw_slots d WHERE d.event_id = e.id)
            """.formatted(EventService.MAX_CONTESTANTS);

    /** Enabled, non-staff player accounts with a confirmed entry payment. */
    private static final String SIGNED_UP = """
            u.enabled = 1
            AND EXISTS (SELECT 1 FROM entry_payments p WHERE p.player_username = u.username)
            AND EXISTS (SELECT 1 FROM authorities a
                        WHERE a.username = u.username AND a.authority = 'ROLE_USER')
            AND NOT EXISTS (SELECT 1 FROM authorities staff WHERE staff.username = u.username
                            AND staff.authority IN ('ROLE_ADMIN', 'ROLE_OFFICIAL'))""";

    private final JdbcTemplate jdbc;
    private final EventUpdates updates;

    public DrawService(JdbcTemplate jdbc, EventUpdates updates) {
        this.jdbc = jdbc;
        this.updates = updates;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<DrawSlot> draw(String eventId) {
        return jdbc.query("SELECT * FROM draw_slots WHERE event_id = ? ORDER BY slot",
                (rs, row) -> new DrawSlot(rs.getString("event_id"), rs.getInt("slot"),
                        rs.getString("player_username"), rs.getBoolean("is_bye")), eventId);
    }

    /** Signed-up players not yet placed in this event's draw. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> unplacedPlayers(String eventId) {
        return jdbc.queryForList("""
                SELECT u.username FROM users u WHERE %s
                AND NOT EXISTS (SELECT 1 FROM draw_slots d
                                WHERE d.event_id = ? AND d.player_username = u.username)
                ORDER BY u.username
                """.formatted(SIGNED_UP), String.class, eventId);
    }

    /** Puts a signed-up player in a slot, replacing whoever was there. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void assign(String eventId, int slot, String playerUsername) {
        if (playerUsername == null || playerUsername.isBlank()) {
            throw new IllegalArgumentException("Choose a player");
        }
        requireUnlocked(eventId);
        Integer eligible = jdbc.queryForObject(
                "SELECT count(*) FROM users u WHERE u.username = ? AND " + SIGNED_UP,
                Integer.class, playerUsername);
        if (eligible == null || eligible == 0) {
            throw new IllegalArgumentException("Only signed-up players can be placed in the draw");
        }
        List<Integer> existing = jdbc.queryForList("""
                SELECT slot FROM draw_slots WHERE event_id = ? AND player_username = ?
                """, Integer.class, eventId, playerUsername);
        if (!existing.isEmpty() && existing.getFirst() != slot) {
            throw new IllegalArgumentException(
                    playerUsername + " is already in slot " + existing.getFirst());
        }
        int changed = jdbc.update("""
                UPDATE draw_slots SET player_username = ?, is_bye = 0 WHERE event_id = ? AND slot = ?
                """, playerUsername, eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    /** Gives a slot a BYE (no opponent). A draw may hold any number of BYEs. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void assignBye(String eventId, int slot) {
        requireUnlocked(eventId);
        int changed = jdbc.update("""
                UPDATE draw_slots SET player_username = NULL, is_bye = 1 WHERE event_id = ? AND slot = ?
                """, eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void clearSlot(String eventId, int slot) {
        requireUnlocked(eventId);
        int changed = jdbc.update("""
                UPDATE draw_slots SET player_username = NULL, is_bye = 0
                WHERE event_id = ? AND slot = ?
                """, eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void clearDraw(String eventId) {
        requireUnlocked(eventId);
        jdbc.update("UPDATE draw_slots SET player_username = NULL, is_bye = 0 WHERE event_id = ?",
                eventId);
        updates.publish(eventId);
    }

    /**
     * Locks a complete draw (every slot holds a player or a BYE, with at least two
     * players) and creates its knockout matches: adjacent slots meet in round 1.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void lock(String eventId) {
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM draw_slots
                WHERE event_id = ? AND player_username IS NULL AND is_bye = 0
                """, Integer.class, eventId);
        if (open != null && open > 0) {
            throw new IllegalArgumentException(open == 1 ? "1 slot is still empty; fill it with a player or a BYE"
                    : open + " slots are still empty; fill them with players or BYEs");
        }
        Integer players = jdbc.queryForObject(
                "SELECT count(*) FROM draw_slots WHERE event_id = ? AND player_username IS NOT NULL",
                Integer.class, eventId);
        if (players == null || players < 2) {
            throw new IllegalArgumentException("Place at least two players before locking the draw");
        }
        int changed = jdbc.update("UPDATE events SET draw_locked = 1 WHERE id = ? AND draw_locked = 0",
                eventId);
        if (changed == 0) throw new IllegalArgumentException("Event not found or already locked");
        MatchService.createBracket(jdbc, eventId,
                SecurityContextHolder.getContext().getAuthentication().getName());
        updates.publish(eventId);
    }

    /**
     * Unlocks the draw and removes its matches. Refused once any match has a result
     * other than the automatic BYE ones, or any result is confirmed.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void unlock(String eventId) {
        Integer played = jdbc.queryForObject("""
                SELECT count(*) FROM matches WHERE event_id = ? AND (confirmed_at IS NOT NULL
                    OR (winner IS NOT NULL AND player1_bye = 0 AND player2_bye = 0))
                """, Integer.class, eventId);
        if (played != null && played > 0) {
            throw new IllegalArgumentException(
                    "Match results have been entered; the draw can no longer be unlocked");
        }
        jdbc.update("DELETE FROM matches WHERE event_id = ?", eventId);
        if (jdbc.update("UPDATE events SET draw_locked = 0 WHERE id = ?", eventId) == 0) {
            throw new IllegalArgumentException("Event not found");
        }
        updates.publish(eventId);
    }

    /** The signed-in player's events, with their slot and first-round opponent. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public List<PlayerDraw> myDraws() {
        String me = SecurityContextHolder.getContext().getAuthentication().getName();
        return jdbc.query("""
                SELECT e.id, e.name, e.draw_locked, d.slot,
                       o.player_username AS opponent, o.is_bye AS opponent_bye
                FROM draw_slots d JOIN events e ON e.id = d.event_id
                LEFT JOIN draw_slots o ON o.event_id = d.event_id
                     AND o.slot = CASE WHEN d.slot % 2 = 1 THEN d.slot + 1 ELSE d.slot - 1 END
                WHERE d.player_username = ?
                ORDER BY e.name
                """, (rs, row) -> new PlayerDraw(rs.getString("id"), rs.getString("name"), rs.getInt("slot"),
                rs.getString("opponent"), rs.getBoolean("opponent_bye"),
                rs.getBoolean("draw_locked")), me);
    }

    private void requireUnlocked(String eventId) {
        Integer locked = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE id = ? AND draw_locked = 1", Integer.class, eventId);
        if (locked != null && locked > 0) {
            throw new IllegalArgumentException("The draw is locked; unlock it to make changes");
        }
    }
}
