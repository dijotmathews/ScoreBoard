package com.dijo.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Event CRUD. Creating an event (or resizing it) creates an empty draw of that size. */
@Service
public class EventService {
    public static final int MAX_CONTESTANTS = 1024;
    /** Valid draw sizes: 2, 4, 8, ... MAX_CONTESTANTS. */
    public static final List<Integer> CONTESTANT_SIZES = IntStream.iterate(2,
            n -> n <= MAX_CONTESTANTS, n -> n * 2).boxed().toList();

    private static final RowMapper<Event> EVENT = (rs, row) -> new Event(rs.getString("id"),
            rs.getString("name"), rs.getInt("contestant_count"), rs.getBoolean("draw_locked"),
            rs.getInt("team_size"));

    private final JdbcTemplate jdbc;
    private final EventUpdates updates;

    public EventService(JdbcTemplate jdbc, EventUpdates updates) {
        this.jdbc = jdbc;
        this.updates = updates;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL', 'USER')")
    public List<Event> events() {
        return jdbc.query("SELECT * FROM events ORDER BY name", EVENT);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN', 'OFFICIAL', 'USER')")
    public Optional<Event> event(String id) {
        return jdbc.query("SELECT * FROM events WHERE id = ?", EVENT, id).stream().findFirst();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<EventSummary> eventSummaries() {
        return jdbc.query("""
                SELECT e.*, (SELECT count(*) FROM draw_slots d WHERE d.event_id = e.id
                             AND (d.player_username IS NOT NULL OR d.is_bye = 1)) AS filled
                FROM events e ORDER BY e.name
                """, (rs, row) -> new EventSummary(EVENT.mapRow(rs, row), rs.getInt("filled")));
    }

    /** A singles event. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public String createEvent(String name, int contestantCount) {
        return createEvent(name, contestantCount, false);
    }

    /** A singles or doubles event; the type cannot be changed afterwards. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public String createEvent(String name, int contestantCount, boolean doubles) {
        validate(name, contestantCount);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO events(id, name, contestant_count, team_size) VALUES (?, ?, ?, ?)",
                id, name, contestantCount, doubles ? 2 : 1);
        createEmptyDraw(id);
        return id;
    }

    /**
     * The contestant count can change only while the draw is empty (and therefore
     * unlocked); the draw is rebuilt.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void updateEvent(String id, String name, int contestantCount) {
        validate(name, contestantCount);
        Event current = event(id).orElseThrow(() -> new IllegalArgumentException("Event not found"));
        boolean resized = current.contestantCount() != contestantCount;
        if (resized && filled(id) > 0) {
            throw new IllegalArgumentException(
                    "Clear the draw before changing the number of contestants");
        }
        jdbc.update("UPDATE events SET name = ?, contestant_count = ? WHERE id = ?",
                name, contestantCount, id);
        if (resized) {
            jdbc.update("DELETE FROM draw_slots WHERE event_id = ?", id);
            createEmptyDraw(id);
        }
        updates.publish(id);
    }

    /** Removes the event together with its draw; a locked draw must be unlocked first. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteEvent(String id) {
        if (event(id).map(Event::drawLocked).orElse(false)) {
            throw new IllegalArgumentException("Unlock the draw before deleting the event");
        }
        jdbc.update("DELETE FROM draw_slots WHERE event_id = ?", id);
        if (jdbc.update("DELETE FROM events WHERE id = ?", id) == 0) {
            throw new IllegalArgumentException("Event not found");
        }
        updates.publish(id);
    }

    public static boolean isValidContestantCount(int count) {
        return count >= 2 && count <= MAX_CONTESTANTS && Integer.bitCount(count) == 1;
    }

    private void createEmptyDraw(String eventId) {
        jdbc.update(DrawService.CREATE_EMPTY_DRAWS, eventId, eventId);
    }

    private int filled(String eventId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM draw_slots
                WHERE event_id = ? AND (player_username IS NOT NULL OR is_bye = 1)
                """, Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private void validate(String name, int contestantCount) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Event name is required");
        if (!isValidContestantCount(contestantCount)) {
            throw new IllegalArgumentException(
                    "Contestants must be a power of 2 from 2 to " + MAX_CONTESTANTS);
        }
    }
}
