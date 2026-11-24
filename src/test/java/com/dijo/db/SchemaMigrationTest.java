package com.dijo.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaMigrationTest extends DatabaseTest {
    @Autowired
    SchemaMigration migration;

    @Test
    void capitalizesNamesStoredBeforeTheyWereCapitalizedOnSave() throws Exception {
        account("abeljoemanoj", "USER");
        jdbc.update("UPDATE users SET full_name = 'ABEL JOE MANOJ' WHERE username = 'abeljoemanoj'");

        migration.afterPropertiesSet();

        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE username = 'abeljoemanoj'",
                String.class)).isEqualTo("Abel Joe Manoj");
    }
}
