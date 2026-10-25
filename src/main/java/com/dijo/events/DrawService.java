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
 * In a doubles event each slot holds a team: the placed player leads it, with a
 * partner. Matches and results refer to the team by its lead.
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

    /**
     * SQL for how a side is shown, given the table aliases of the lead's and the
     * partner's users rows: "Ada & Bob", or just "Ada" without a partner; NULL without a lead.
     */
    static String teamName(String lead, String partner) {
        return ("(COALESCE(%1$s.full_name, %1$s.username)"
                + " || COALESCE(' & ' || COALESCE(%2$s.full_name, %2$s.username), ''))").formatted(lead, partner);
    }

    public DrawService(JdbcTemplate jdbc, EventUpdates updates) {
        this.jdbc = jdbc;
        this.updates = updates;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<DrawSlot> draw(String eventId) {
        return jdbc.query("""
                SELECT d.*, COALESCE(u.full_name, u.username) AS player_name,
                       COALESCE(p.full_name, p.username) AS partner_name
                FROM draw_slots d LEFT JOIN users u ON u.username = d.player_username
                LEFT JOIN users p ON p.username = d.partner_username
                WHERE d.event_id = ? ORDER BY d.slot
                """, (rs, row) -> new DrawSlot(rs.getString("event_id"), rs.getInt("slot"),
                        rs.getString("player_username"), rs.getString("player_name"),
                        rs.getString("partner_username"), rs.getString("partner_name"),
                        rs.getBoolean("is_bye")), eventId);
    }

    /** Signed-up players not yet placed in this event's draw, as a player or a partner. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<Player> unplacedPlayers(String eventId) {
        return jdbc.query("""
                SELECT u.username, COALESCE(u.full_name, u.username) AS name FROM users u WHERE %s
                AND NOT EXISTS (SELECT 1 FROM draw_slots d WHERE d.event_id = ?
                                AND u.username IN (d.player_username, d.partner_username))
                ORDER BY name COLLATE NOCASE, u.username
                """.formatted(SIGNED_UP),
                (rs, row) -> new Player(rs.getString("username"), rs.getString("name")), eventId);
    }

    /**
     * Puts a signed-up player in a slot, replacing whoever was there. In doubles the
     * slot keeps its partner, unless the partner is the player being placed.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void assign(String eventId, int slot, String playerUsername) {
        if (playerUsername == null || playerUsername.isBlank()) {
            throw new IllegalArgumentException("Choose a player");
        }
        requireUnlocked(eventId);
        requireSignedUp(playerUsername);
        requireNotPlacedElsewhere(eventId, slot, playerUsername);
        int changed = jdbc.update("""
                UPDATE draw_slots SET player_username = ?, is_bye = 0,
                    partner_username = CASE WHEN partner_username = ? THEN NULL ELSE partner_username END
                WHERE event_id = ? AND slot = ?
                """, playerUsername, playerUsername, eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    /** Doubles only: makes a signed-up player the partner of the player in a slot. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void assignPartner(String eventId, int slot, String partnerUsername) {
        if (partnerUsername == null || partnerUsername.isBlank()) {
            throw new IllegalArgumentException("Choose a partner");
        }
        requireUnlocked(eventId);
        requireDoubles(eventId);
        List<String> leads = jdbc.queryForList(
                "SELECT player_username FROM draw_slots WHERE event_id = ? AND slot = ?",
                String.class, eventId, slot);
        if (leads.isEmpty()) throw new IllegalArgumentException("Draw slot not found");
        String lead = leads.getFirst();
        if (lead == null) throw new IllegalArgumentException("Place a player in the slot first");
        if (lead.equals(partnerUsername)) {
            throw new IllegalArgumentException("A player cannot partner themselves");
        }
        requireSignedUp(partnerUsername);
        requireNotPlacedElsewhere(eventId, slot, partnerUsername);
        jdbc.update("UPDATE draw_slots SET partner_username = ? WHERE event_id = ? AND slot = ?",
                partnerUsername, eventId, slot);
        updates.publish(eventId);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void clearPartner(String eventId, int slot) {
        requireUnlocked(eventId);
        int changed = jdbc.update(
                "UPDATE draw_slots SET partner_username = NULL WHERE event_id = ? AND slot = ?",
                eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    private void requireSignedUp(String username) {
        Integer eligible = jdbc.queryForObject(
                "SELECT count(*) FROM users u WHERE u.username = ? AND " + SIGNED_UP,
                Integer.class, username);
        if (eligible == null || eligible == 0) {
            throw new IllegalArgumentException("Only signed-up players can be placed in the draw");
        }
    }

    /** A player takes one place per event: as a player or a partner, in one slot. */
    private void requireNotPlacedElsewhere(String eventId, int slot, String username) {
        List<Integer> existing = jdbc.queryForList("""
                SELECT slot FROM draw_slots WHERE event_id = ? AND slot <> ?
                AND ? IN (player_username, partner_username)
                """, Integer.class, eventId, slot, username);
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException(nameOf(username) + " is already in slot " + existing.getFirst());
        }
    }

    private void requireDoubles(String eventId) {
        Integer doubles = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE id = ? AND team_size = 2", Integer.class, eventId);
        if (doubles == null || doubles == 0) {
            throw new IllegalArgumentException("Partners can only be placed in a doubles event");
        }
    }

    private String nameOf(String username) {
        return jdbc.queryForList("SELECT COALESCE(full_name, username) FROM users WHERE username = ?",
                String.class, username).stream().findFirst().orElse(username);
    }

    /** Gives a slot a BYE (no opponent). A draw may hold any number of BYEs. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void assignBye(String eventId, int slot) {
        requireUnlocked(eventId);
        int changed = jdbc.update("""
                UPDATE draw_slots SET player_username = NULL, partner_username = NULL, is_bye = 1
                WHERE event_id = ? AND slot = ?
                """, eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void clearSlot(String eventId, int slot) {
        requireUnlocked(eventId);
        int changed = jdbc.update("""
                UPDATE draw_slots SET player_username = NULL, partner_username = NULL, is_bye = 0
                WHERE event_id = ? AND slot = ?
                """, eventId, slot);
        if (changed == 0) throw new IllegalArgumentException("Draw slot not found");
        updates.publish(eventId);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void clearDraw(String eventId) {
        requireUnlocked(eventId);
        jdbc.update("""
                UPDATE draw_slots SET player_username = NULL, partner_username = NULL, is_bye = 0
                WHERE event_id = ?
                """, eventId);
        updates.publish(eventId);
    }

    /**
     * Locks a complete draw (every slot holds a player or a BYE, with at least two
     * players, and in doubles every player has a partner) and creates its knockout
     * matches: adjacent slots meet in round 1.
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
        Integer missingPartner = jdbc.queryForObject("""
                SELECT count(*) FROM draw_slots d JOIN events e ON e.id = d.event_id
                WHERE d.event_id = ? AND e.team_size = 2
                AND d.player_username IS NOT NULL AND d.partner_username IS NULL
                """, Integer.class, eventId);
        if (missingPartner != null && missingPartner > 0) {
            throw new IllegalArgumentException(missingPartner == 1 ? "1 team is missing a partner"
                    : missingPartner + " teams are missing a partner");
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

    /**
     * The signed-in player's events (as a player or a partner), with their slot and
     * first-round opponent, shown as a team in doubles.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public List<PlayerDraw> myDraws() {
        String me = SecurityContextHolder.getContext().getAuthentication().getName();
        return jdbc.query("""
                SELECT e.id, e.name, e.draw_locked, d.slot,
                       o.player_username AS opponent, o.is_bye AS opponent_bye,
                       %s AS opponent_name
                FROM draw_slots d JOIN events e ON e.id = d.event_id
                LEFT JOIN draw_slots o ON o.event_id = d.event_id
                     AND o.slot = CASE WHEN d.slot %% 2 = 1 THEN d.slot + 1 ELSE d.slot - 1 END
                LEFT JOIN users u ON u.username = o.player_username
                LEFT JOIN users up ON up.username = o.partner_username
                WHERE ? IN (d.player_username, d.partner_username)
                ORDER BY e.name
                """.formatted(teamName("u", "up")), (rs, row) -> new PlayerDraw(rs.getString("id"), rs.getString("name"), rs.getInt("slot"),
                rs.getString("opponent"), rs.getString("opponent_name"), rs.getBoolean("opponent_bye"),
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
