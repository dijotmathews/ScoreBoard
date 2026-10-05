from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[6]  # project root
def sql_blocks(path):
    return re.findall(r'"""(.*?)"""', (root / path).read_text(), re.S)

blocks = sql_blocks('src/main/java/com/dijo/benefits/PlayerBenefitsService.java')
payment_sql = next(s for s in blocks if 'INSERT INTO entry_payments' in s)
kit_sql = next(s for s in blocks if 'UPDATE welcome_kits' in s)
coupon_sql = next(s for s in blocks if 'UPDATE player_coupons' in s)
list_sql = next(s for s in blocks if 'SELECT u.username, u.enabled' in s)
draw_sql = next(s for s in sql_blocks('src/main/java/com/dijo/events/DrawService.java')
                if 'WITH RECURSIVE' in s).replace('%d', '1024')

db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
db.executescript('''
CREATE TABLE users(username TEXT PRIMARY KEY, password TEXT, enabled INTEGER);
CREATE TABLE authorities(username TEXT, authority TEXT);
INSERT INTO users VALUES ('official','hash',1),('player','hash',1),('other','hash',1),('off','hash',0);
INSERT INTO authorities VALUES ('player','ROLE_USER'),('other','ROLE_USER'),('off','ROLE_USER');
''')
db.executescript((root / 'src/main/resources/benefits-schema.sql').read_text())
db.executescript((root / 'src/main/resources/events-schema.sql').read_text())

# Sign-up needs benefit settings; the fee must match.
def pay(id, ref, player='player', amount=50000):
    return db.execute(payment_sql, (id, ref, amount, 'official', 'now', player, amount)).rowcount
assert pay('p0', 'r0') == 0  # no settings yet
db.execute("INSERT INTO benefit_settings VALUES (1, 50000, 2, 'Refreshment')")
try:
    db.execute("INSERT INTO benefit_settings VALUES (2, 1, 0, 'x')")
    raise AssertionError('only one settings row')
except sqlite3.IntegrityError:
    pass
assert pay('p', 'r') == 1
db.execute("INSERT INTO welcome_kits(payment_id) VALUES ('p')")
db.execute("INSERT INTO player_coupons VALUES ('c','p','Refreshment',NULL,NULL)")
assert pay('p2', 'r') == 0            # same reference
assert pay('p3', 'different') == 0    # one sign-up per player
assert pay('p4', 'r', 'other') == 0   # reference reused by another player
assert pay('p5', 'new', 'other', 49999) == 0  # wrong fee
assert pay('p6', 'new', 'official') == 0      # not a player
assert pay('p7', 'new', 'off') == 0           # disabled
assert db.execute(kit_sql, ('first', 'official', 'player')).rowcount == 1
assert db.execute(kit_sql, ('second', 'official', 'player')).rowcount == 0
assert db.execute(coupon_sql, ('first', 'official', 'c', 'other')).rowcount == 0
assert db.execute(coupon_sql, ('first', 'official', 'c', 'player')).rowcount == 1
assert db.execute(coupon_sql, ('second', 'official', 'c', 'player')).rowcount == 0
assert db.execute("SELECT redeemed_at FROM player_coupons WHERE code='c'").fetchone()[0] == 'first'
db.commit()

try:
    with db:
        assert pay('rollback', 'fresh', 'other') == 1
        db.execute("INSERT INTO welcome_kits(payment_id) VALUES ('rollback')")
        db.execute("INSERT INTO player_coupons VALUES ('c','rollback','duplicate',NULL,NULL)")
except sqlite3.IntegrityError:
    pass
assert db.execute("SELECT count(*) FROM entry_payments WHERE id='rollback'").fetchone()[0] == 0
try:
    db.execute("INSERT INTO welcome_kits(payment_id) VALUES ('nonexistent')")
    raise AssertionError('Foreign key should fail')
except sqlite3.IntegrityError:
    pass

rows = {r[0]: r for r in db.execute(list_sql)}
assert set(rows) == {'player', 'other', 'off'}
assert rows['player'][2] == 'p' and rows['player'][6:8] == (1, 1)
assert rows['other'][2] is None and rows['other'][6:8] == (0, 0)
db.execute("INSERT INTO authorities VALUES ('player','ROLE_ADMIN')")
assert 'player' not in {r[0] for r in db.execute(list_sql)}
db.execute("DELETE FROM authorities WHERE authority = 'ROLE_ADMIN'")

# Events: power-of-2 draw sizes only, each with an empty draw of that size.
for bad in (0, 1, 3, 6, 12, None):
    try:
        db.execute("INSERT INTO events(id, name, contestant_count) VALUES ('bad', 'Bad', ?)", (bad,))
        raise AssertionError(f'contestant_count {bad} should fail')
    except sqlite3.IntegrityError:
        pass
db.execute("INSERT INTO events(id, name, contestant_count) VALUES ('singles', 'Singles', 4)")
db.execute("INSERT INTO events(id, name, contestant_count) VALUES ('doubles', 'Doubles', 2)")
db.execute(draw_sql, (None, None))  # all events
assert db.execute("SELECT count(*), min(slot), max(slot) FROM draw_slots WHERE event_id='singles'").fetchone() == (4, 1, 4)
assert db.execute("SELECT count(*) FROM draw_slots WHERE event_id='doubles'").fetchone()[0] == 2
db.execute(draw_sql, ('singles', 'singles'))
assert db.execute("SELECT count(*) FROM draw_slots WHERE event_id='singles'").fetchone()[0] == 4  # idempotent
db.execute("INSERT INTO events(id, name, contestant_count) VALUES ('big', 'Big', 1024)")
db.execute(draw_sql, ('big', 'big'))
assert db.execute("SELECT count(*) FROM draw_slots WHERE event_id='big'").fetchone()[0] == 1024

# A player can hold one slot per event, and take part in several events.
db.execute("UPDATE draw_slots SET player_username='player' WHERE event_id='singles' AND slot=1")
db.execute("UPDATE draw_slots SET player_username='player' WHERE event_id='doubles' AND slot=2")
try:
    db.execute("UPDATE draw_slots SET player_username='player' WHERE event_id='singles' AND slot=2")
    raise AssertionError('a player can hold only one slot per event')
except sqlite3.IntegrityError:
    pass
# BYEs: a slot is a player or a BYE, never both; several slots can be BYEs.
try:
    db.execute("UPDATE draw_slots SET is_bye=1 WHERE event_id='singles' AND slot=1")  # has player
    raise AssertionError('a slot cannot hold a player and a BYE')
except sqlite3.IntegrityError:
    pass
db.execute("UPDATE draw_slots SET is_bye=1 WHERE event_id='big' AND slot IN (2, 4, 6)")
assert db.execute("SELECT count(*) FROM draw_slots WHERE event_id='big' AND is_bye=1").fetchone()[0] == 3
try:
    db.execute("UPDATE events SET draw_locked=2 WHERE id='big'")
    raise AssertionError('draw_locked is 0 or 1')
except sqlite3.IntegrityError:
    pass
assert db.execute("SELECT draw_locked FROM events WHERE id='big'").fetchone()[0] == 0
# Matches: the winner is one of the two players; a BYE side has no player;
# a result is confirmed only once there is a winner.
db.execute("INSERT INTO matches(event_id, round, match_no, player1, player2) VALUES ('singles', 1, 1, 'player', 'other')")
db.execute("INSERT INTO matches(event_id, round, match_no, player1, player2_bye) VALUES ('singles', 1, 2, 'player', 1)")
def fails(sql):
    try:
        db.execute(sql)
        raise AssertionError('should fail: ' + sql)
    except sqlite3.IntegrityError:
        pass
fails("UPDATE matches SET winner='official' WHERE event_id='singles' AND match_no=1")
fails("UPDATE matches SET confirmed_at='now' WHERE event_id='singles' AND match_no=1")
fails("UPDATE matches SET player2='other' WHERE event_id='singles' AND match_no=2")  # BYE side
fails("INSERT INTO matches(event_id, round, match_no) VALUES ('singles', 1, 1)")      # duplicate
db.execute("UPDATE matches SET winner='other', confirmed_at='now' WHERE event_id='singles' AND match_no=1")
print('PASS: sign-up payments, benefits, admin listing; power-of-2 events with empty draws; '
      'one slot per player per event across several events; BYEs; draw lock flag; match constraints')
