CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1
    CHECK (enabled IN (0, 1))
);

CREATE TABLE IF NOT EXISTS authorities (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL,
    authority TEXT NOT NULL,

    UNIQUE (username, authority),

    FOREIGN KEY (username)
        REFERENCES users(username)
        ON DELETE CASCADE
);
