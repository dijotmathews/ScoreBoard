package com.dijo.events;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knockout matches of a locked draw. Officials record results for matches they
 * officiate; only an admin confirms a result, which eliminates the loser (or the
 * BYE) and moves the winner into the next round.
 */
@Service
public class MatchService {
    private static final int MAX_SCORE_LENGTH = 100;
    private static final int MAX_COURT_LENGTH = 20;
    private static final RowMapper<Match> MATCH = (rs, row) -> new Match(rs.getString("event_id"),
            rs.getString("event_name"),
            Integer.numberOfTrailingZeros(rs.getInt("contestant_count")),
            rs.getInt("round"), rs.getInt("match_no"),
            rs.getString("player1"), rs.getString("player1_name"), rs.getBoolean("player1_bye"),
            rs.getString("player2"), rs.getString("player2_name"), rs.getBoolean("player2_bye"),
            rs.getString("official"), rs.getString("winner"), rs.getString("winner_name"),
            rs.getString("score"),
            rs.getString("result_by"), rs.getString("confirmed_by"), rs.getString("court"),
            rs.getString("start_time") == null ? null : LocalTime.parse(rs.getString("start_time")));
    private static final String SELECT = """
            SELECT m.*, e.name AS event_name, e.contestant_count,
                   COALESCE(p1.full_name, p1.username) AS player1_name,
                   COALESCE(p2.full_name, p2.username) AS player2_name,
                   COALESCE(w.full_name, w.username) AS winner_name
            FROM matches m JOIN events e ON e.id = m.event_id
            LEFT JOIN users p1 ON p1.username = m.player1
            LEFT JOIN users p2 ON p2.username = m.player2
            LEFT JOIN users w ON w.username = m.winner
            """;

    private final JdbcTemplate jdbc;
    private final EventUpdates updates;

    public MatchService(JdbcTemplate jdbc, EventUpdates updates) {
        this.jdbc = jdbc;
        this.updates = updates;
    }

    /**
     * Builds every match of the knockout from a complete draw: round 1 pairs adjacent
     * slots, later rounds start empty. A match between two BYEs is not played and sends
     * a BYE to the next round. A player facing a BYE is entered as the winner and waits
     * for the admin's confirmation. resultBy may be null (migrations).
     */
    public static void createBracket(JdbcTemplate jdbc, String eventId, String resultBy) {
        List<Map<String, Object>> slots = jdbc.queryForList(
                "SELECT player_username, is_bye FROM draw_slots WHERE event_id = ? ORDER BY slot", eventId);
        String now = Instant.now().toString();
        // byes[i]: side i of the current round is a BYE; players are known only in round 1.
        boolean[] byes = new boolean[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            byes[i] = ((Number) slots.get(i).get("is_bye")).intValue() == 1;
        }
        for (int round = 1; byes.length >= 2; round++) {
            boolean[] next = new boolean[byes.length / 2];
            for (int m = 1; m <= byes.length / 2; m++) {
                boolean bye1 = byes[2 * m - 2], bye2 = byes[2 * m - 1];
                String p1 = round == 1 ? (String) slots.get(2 * m - 2).get("player_username") : null;
                String p2 = round == 1 ? (String) slots.get(2 * m - 1).get("player_username") : null;
                String winner = bye1 && !bye2 ? p2 : bye2 && !bye1 ? p1 : null;
                jdbc.update("""
                        INSERT INTO matches(event_id, round, match_no, player1, player1_bye,
                            player2, player2_bye, winner, result_by, result_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, eventId, round, m, p1, bye1 ? 1 : 0, p2, bye2 ? 1 : 0, winner,
                        winner == null ? null : resultBy, winner == null ? null : now);
                next[m - 1] = bye1 && bye2;
            }
            byes = next;
        }
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<Match> matches(String eventId) {
        return jdbc.query(SELECT + " WHERE m.event_id = ? ORDER BY m.round, m.match_no",
                MATCH, eventId);
    }

    /** Matches the signed-in official is assigned to. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OFFICIAL')")
    public List<Match> myMatches() {
        return jdbc.query(SELECT + " WHERE m.official = ? ORDER BY e.name, m.round, m.match_no",
                MATCH, actor());
    }

    /** Confirmed and total match counts across all events. */
    public record MatchTotals(int confirmed, int total) {}

    /** Matches between two BYEs are not played, so they are not counted. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public MatchTotals totals() {
        return jdbc.queryForObject("""
                SELECT count(confirmed_at) AS confirmed, count(*) AS total FROM matches
                WHERE NOT (player1_bye = 1 AND player2_bye = 1)
                """, (rs, row) -> new MatchTotals(rs.getInt("confirmed"), rs.getInt("total")));
    }

    /** The whole knockout of an event, for anyone signed in; empty until the draw is locked. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL', 'USER')")
    public List<Match> bracket(String eventId) {
        return jdbc.query(SELECT + " WHERE m.event_id = ? AND e.draw_locked = 1"
                + " ORDER BY m.round, m.match_no", MATCH, eventId);
    }

    /** Matches the signed-in player plays in, across all events, in round order. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public List<Match> myPlayerMatches() {
        String me = actor();
        return jdbc.query(SELECT + " WHERE m.player1 = ? OR m.player2 = ? ORDER BY e.name, m.round",
                MATCH, me, me);
    }

    /** Enabled accounts with ROLE_OFFICIAL. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> officials() {
        return jdbc.queryForList("""
                SELECT u.username FROM users u WHERE u.enabled = 1 AND EXISTS
                (SELECT 1 FROM authorities a WHERE a.username = u.username
                 AND a.authority = 'ROLE_OFFICIAL')
                ORDER BY u.username
                """, String.class);
    }

    /**
     * Matches still to be played in every locked draw (see {@link Match#remaining()}), by
     * event name. Within an event, matches with a court or time come first, earliest
     * start time first; the rest follow in round order.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<Match> remainingMatches() {
        return jdbc.query(SELECT + """
                 WHERE e.draw_locked = 1 AND m.confirmed_at IS NULL
                 AND m.player1_bye = 0 AND m.player2_bye = 0
                 ORDER BY e.name COLLATE NOCASE, e.id,
                          (m.court IS NULL AND m.start_time IS NULL),
                          m.start_time IS NULL, m.start_time, m.round, m.match_no
                """, MATCH);
    }

    /**
     * Sets where and when a match is to be played. A blank court or a null time clears
     * it. Only matches still to be played can be scheduled.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void schedule(String eventId, int round, int matchNo, String court, LocalTime startTime) {
        Match match = match(eventId, round, matchNo);
        if (!match.remaining()) throw new IllegalArgumentException("This match is not to be played");
        String cleanCourt = court == null || court.isBlank() ? null : court.strip();
        if (cleanCourt != null && cleanCourt.length() > MAX_COURT_LENGTH) {
            throw new IllegalArgumentException("Court must be at most " + MAX_COURT_LENGTH + " characters");
        }
        jdbc.update("""
                UPDATE matches SET court = ?, start_time = ?
                WHERE event_id = ? AND round = ? AND match_no = ?
                """, cleanCourt, startTime == null ? null : startTime.withSecond(0).withNano(0).toString(),
                eventId, round, matchNo);
    }

    /** official == null removes the assignment. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void assignOfficial(String eventId, int round, int matchNo, String official) {
        Match match = match(eventId, round, matchNo);
        if (match.confirmed()) throw new IllegalArgumentException("The result is already confirmed");
        if (official != null && !officials().contains(official)) {
            throw new IllegalArgumentException("Only enabled officials can officiate a match");
        }
        jdbc.update("UPDATE matches SET official = ? WHERE event_id = ? AND round = ? AND match_no = ?",
                official, eventId, round, matchNo);
    }

    /**
     * Enters or updates the result of an unconfirmed match. An official may do this
     * only for matches they officiate; an admin for any match.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL')")
    public void recordResult(String eventId, int round, int matchNo, String winner, String score) {
        Match match = match(eventId, round, matchNo);
        if (!isAdmin() && !actor().equals(match.official())) {
            throw new IllegalArgumentException("You are not the official for this match");
        }
        if (match.confirmed()) throw new IllegalArgumentException("The result is already confirmed");
        if (match.byeMatch()) {
            throw new IllegalArgumentException("A BYE match needs only the admin's confirmation");
        }
        if (!match.ready()) throw new IllegalArgumentException("Both players are not known yet");
        if (winner == null || !(winner.equals(match.player1()) || winner.equals(match.player2()))) {
            throw new IllegalArgumentException("Choose the winner");
        }
        String cleanScore = score == null || score.isBlank() ? null : score.strip();
        if (cleanScore != null && cleanScore.length() > MAX_SCORE_LENGTH) {
            throw new IllegalArgumentException("Score must be at most " + MAX_SCORE_LENGTH + " characters");
        }
        jdbc.update("""
                UPDATE matches SET winner = ?, score = ?, result_by = ?, result_at = ?
                WHERE event_id = ? AND round = ? AND match_no = ?
                """, winner, cleanScore, actor(), Instant.now().toString(), eventId, round, matchNo);
        updates.publish(eventId);
    }

    /** Confirms the entered result: the loser is eliminated and the winner advances. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void confirmResult(String eventId, int round, int matchNo) {
        Match match = match(eventId, round, matchNo);
        if (match.confirmed()) throw new IllegalArgumentException("The result is already confirmed");
        if (match.winner() == null) throw new IllegalArgumentException("No result has been entered yet");
        String now = Instant.now().toString();
        jdbc.update("""
                UPDATE matches SET confirmed_by = ?, confirmed_at = ?
                WHERE event_id = ? AND round = ? AND match_no = ?
                """, actor(), now, eventId, round, matchNo);
        if (round < match.totalRounds()) {
            int next = (matchNo + 1) / 2;
            jdbc.update("UPDATE matches SET " + nextSide(matchNo)
                            + " = ? WHERE event_id = ? AND round = ? AND match_no = ?",
                    match.winner(), eventId, round + 1, next);
            // Facing a BYE there too: they go through, pending the admin's confirmation.
            jdbc.update("""
                    UPDATE matches SET winner = ?, result_by = ?, result_at = ?
                    WHERE event_id = ? AND round = ? AND match_no = ? AND (player1_bye = 1 OR player2_bye = 1)
                    """, match.winner(), actor(), now, eventId, round + 1, next);
        }
        updates.publish(eventId);
    }

    /**
     * Undoes a confirmation while the winner's next match has no result yet, other
     * than going through against a BYE, which is undone with it.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void reopen(String eventId, int round, int matchNo) {
        Match match = match(eventId, round, matchNo);
        if (!match.confirmed()) throw new IllegalArgumentException("The result is not confirmed");
        if (round < match.totalRounds()) {
            Match next = match(eventId, round + 1, (matchNo + 1) / 2);
            if (next.confirmed() || (next.winner() != null && !next.byeMatch())) {
                throw new IllegalArgumentException("The next-round match already has a result");
            }
            jdbc.update("UPDATE matches SET " + nextSide(matchNo) + " = NULL, winner = NULL,"
                            + " result_by = NULL, result_at = NULL"
                            + " WHERE event_id = ? AND round = ? AND match_no = ?",
                    eventId, round + 1, next.matchNo());
        }
        jdbc.update("""
                UPDATE matches SET confirmed_by = NULL, confirmed_at = NULL
                WHERE event_id = ? AND round = ? AND match_no = ?
                """, eventId, round, matchNo);
        updates.publish(eventId);
    }

    /**
     * The latest state of one match, or empty if it no longer exists (e.g. the draw
     * was unlocked). Officials can read only the matches they officiate.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL')")
    public Optional<Match> currentMatch(String eventId, int round, int matchNo) {
        return jdbc.query(SELECT + " WHERE m.event_id = ? AND m.round = ? AND m.match_no = ?",
                        MATCH, eventId, round, matchNo)
                .stream().findFirst()
                .filter(m -> isAdmin() || actor().equals(m.official()));
    }

    private Match match(String eventId, int round, int matchNo) {
        return jdbc.query(SELECT + " WHERE m.event_id = ? AND m.round = ? AND m.match_no = ?",
                        MATCH, eventId, round, matchNo)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Match not found"));
    }

    /** Winners of odd-numbered matches take the first side of the next match. */
    private static String nextSide(int matchNo) {
        return matchNo % 2 == 1 ? "player1" : "player2";
    }

    private static boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Authentication required");
        }
        return authentication.getName();
    }
}
