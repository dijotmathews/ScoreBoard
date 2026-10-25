package com.dijo.db;

import com.dijo.events.DrawService;
import com.dijo.events.EventService;
import com.dijo.events.MatchService;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * Upgrades databases from the per-event benefits schema (benefit_events, entry_payments
 * keyed by event) to sign-up based benefits with separate events. Runs once, in one
 * transaction. Then adds columns introduced later and makes sure every event has its draw.
 */
@Component
@DependsOnDatabaseInitialization
public class SchemaMigration implements InitializingBean {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public SchemaMigration(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        if (tableExists(jdbc, "benefit_events")) migrateFromEventBenefits();
        addColumnIfMissing("users", "full_name", "TEXT");
        addColumnIfMissing("matches", "court", "TEXT");
        addColumnIfMissing("matches", "start_time", "TEXT");
        addColumnIfMissing("events", "draw_locked",
                "INTEGER NOT NULL DEFAULT 0 CHECK(draw_locked IN (0, 1))");
        addColumnIfMissing("draw_slots", "is_bye", "INTEGER NOT NULL DEFAULT 0"
                + " CHECK(is_bye IN (0, 1) AND (is_bye = 0 OR player_username IS NULL))");
        jdbc.update(DrawService.CREATE_EMPTY_DRAWS, null, null);
        // Draws locked before matches existed get their bracket now.
        for (String eventId : jdbc.queryForList("""
                SELECT id FROM events e WHERE draw_locked = 1
                AND NOT EXISTS (SELECT 1 FROM matches m WHERE m.event_id = e.id)
                """, String.class)) {
            MatchService.createBracket(jdbc, eventId, null);
        }
    }

    private void migrateFromEventBenefits() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // Tables are rebuilt, so foreign keys are checked once at the end instead.
            statement.execute("PRAGMA foreign_keys = OFF");
            connection.setAutoCommit(false);
            try {
                migrate(connection, new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
                connection.commit();
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA legacy_alter_table = OFF");
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private void migrate(Connection connection, JdbcTemplate tx) {
        List<String> repeated = tx.queryForList("""
                SELECT player_username FROM entry_payments
                GROUP BY player_username HAVING count(*) > 1
                """, String.class);
        if (!repeated.isEmpty()) {
            throw new IllegalStateException("Cannot migrate to one sign-up per player: "
                    + repeated + " paid for more than one event. Resolve these payments first.");
        }

        // Benefits come from the event most players paid for.
        tx.update("""
                INSERT INTO benefit_settings(id, fee_paise, coupon_count, coupon_description)
                SELECT 1, e.fee_paise, e.coupon_count, e.coupon_description FROM benefit_events e
                ORDER BY (SELECT count(*) FROM entry_payments p WHERE p.event_id = e.id) DESC, e.name
                LIMIT 1
                ON CONFLICT(id) DO NOTHING
                """);

        for (Map<String, Object> event : tx.queryForList("SELECT id, name, contestant_count FROM benefit_events")) {
            Number count = (Number) event.get("contestant_count");
            tx.update("INSERT INTO events(id, name, contestant_count) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    event.get("id"), event.get("name"), drawSize(count == null ? 0 : count.intValue()));
        }

        // Rename without rewriting the references in welcome_kits and player_coupons,
        // so they point at the rebuilt entry_payments.
        tx.execute("PRAGMA legacy_alter_table = ON");
        tx.execute("ALTER TABLE entry_payments RENAME TO legacy_entry_payments");
        boolean hadDraws = tableExists(tx, "draw_slots");
        if (hadDraws) tx.execute("ALTER TABLE draw_slots RENAME TO legacy_draw_slots");
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("benefits-schema.sql"));
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("events-schema.sql"));

        tx.update("""
                INSERT INTO entry_payments
                (id, player_username, payment_reference, amount_paise, confirmed_by, confirmed_at)
                SELECT id, player_username, payment_reference, amount_paise, confirmed_by, confirmed_at
                FROM legacy_entry_payments
                """);
        if (hadDraws) {
            tx.update("""
                    INSERT INTO draw_slots(event_id, slot, player_username)
                    SELECT d.event_id, d.slot, d.player_username FROM legacy_draw_slots d
                    JOIN events e ON e.id = d.event_id WHERE d.slot <= e.contestant_count
                    """);
            tx.execute("DROP TABLE legacy_draw_slots");
        }
        tx.execute("DROP TABLE legacy_entry_payments");
        tx.execute("DROP TABLE benefit_events");

        List<Map<String, Object>> broken = tx.queryForList("PRAGMA foreign_key_check");
        if (!broken.isEmpty()) {
            throw new IllegalStateException("Migration left broken references: " + broken);
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean present = !jdbc.queryForList(
                "SELECT name FROM pragma_table_info(?) WHERE name = ?", String.class, table, column)
                .isEmpty();
        if (!present) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    /** Smallest valid draw size that fits the old count. */
    private static int drawSize(int count) {
        int size = 2;
        while (size < count && size < EventService.MAX_CONTESTANTS) size *= 2;
        return size;
    }

    private static boolean tableExists(JdbcTemplate template, String table) {
        Integer count = template.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table);
        return count != null && count > 0;
    }
}
