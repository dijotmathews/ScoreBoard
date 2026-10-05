CREATE TABLE IF NOT EXISTS events (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    -- Draw size: a power of 2.
    contestant_count INTEGER NOT NULL
        CHECK(contestant_count >= 2 AND (contestant_count & (contestant_count - 1)) = 0),
    -- A locked draw cannot be changed until it is unlocked.
    draw_locked INTEGER NOT NULL DEFAULT 0 CHECK(draw_locked IN (0, 1))
);
-- A signed-up player takes part in an event by holding a slot in its draw.
-- A slot holds a player, a BYE, or nothing yet.
CREATE TABLE IF NOT EXISTS draw_slots (
    event_id TEXT NOT NULL REFERENCES events(id),
    slot INTEGER NOT NULL CHECK(slot >= 1),
    player_username TEXT REFERENCES users(username),
    is_bye INTEGER NOT NULL DEFAULT 0
        CHECK(is_bye IN (0, 1) AND (is_bye = 0 OR player_username IS NULL)),
    PRIMARY KEY(event_id, slot),
    UNIQUE(event_id, player_username)
);
-- Knockout matches, created when a draw is locked. Round 1 pairs adjacent slots;
-- the winner of match m moves to match (m + 1) / 2 of the next round.
-- A side is a player, a BYE (round 1 only), or not known yet (NULL).
CREATE TABLE IF NOT EXISTS matches (
    event_id TEXT NOT NULL REFERENCES events(id),
    round INTEGER NOT NULL CHECK(round >= 1),
    match_no INTEGER NOT NULL CHECK(match_no >= 1),
    player1 TEXT REFERENCES users(username),
    player1_bye INTEGER NOT NULL DEFAULT 0 CHECK(player1_bye IN (0, 1)),
    player2 TEXT REFERENCES users(username),
    player2_bye INTEGER NOT NULL DEFAULT 0 CHECK(player2_bye IN (0, 1)),
    official TEXT REFERENCES users(username),
    winner TEXT REFERENCES users(username),
    score TEXT,
    result_by TEXT REFERENCES users(username),
    result_at TEXT,
    confirmed_by TEXT REFERENCES users(username),
    confirmed_at TEXT,
    PRIMARY KEY(event_id, round, match_no),
    CHECK(player1_bye = 0 OR player1 IS NULL),
    CHECK(player2_bye = 0 OR player2 IS NULL),
    CHECK(winner IS NULL OR winner = player1 OR winner = player2),
    CHECK(confirmed_at IS NULL OR winner IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_matches_official ON matches(official);
