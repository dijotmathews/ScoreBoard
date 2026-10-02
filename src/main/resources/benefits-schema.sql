CREATE TABLE IF NOT EXISTS benefit_events (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    fee_paise INTEGER NOT NULL CHECK(fee_paise > 0),
    coupon_count INTEGER NOT NULL CHECK(coupon_count BETWEEN 0 AND 100),
    coupon_description TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS entry_payments (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES benefit_events(id),
    player_username TEXT NOT NULL REFERENCES users(username),
    payment_reference TEXT NOT NULL UNIQUE,
    amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
    confirmed_by TEXT NOT NULL REFERENCES users(username),
    confirmed_at TEXT NOT NULL,
    UNIQUE(event_id, player_username)
);
CREATE TABLE IF NOT EXISTS welcome_kits (
    payment_id TEXT PRIMARY KEY REFERENCES entry_payments(id),
    handed_over_at TEXT,
    handed_over_by TEXT REFERENCES users(username)
);
CREATE TABLE IF NOT EXISTS player_coupons (
    code TEXT PRIMARY KEY,
    payment_id TEXT NOT NULL REFERENCES entry_payments(id),
    description TEXT NOT NULL,
    redeemed_at TEXT,
    redeemed_by TEXT REFERENCES users(username)
);
CREATE INDEX IF NOT EXISTS idx_coupons_payment ON player_coupons(payment_id);
