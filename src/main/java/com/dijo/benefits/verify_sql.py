from pathlib import Path
import re
import sqlite3

root = Path(__file__).parent
source = (root / 'src/main/java/com/dijo/benefits/PlayerBenefitsService.java').read_text()
blocks = re.findall(r'"""(.*?)"""', source, re.S)
payment_sql = next(s for s in blocks if 'INSERT INTO entry_payments' in s)
kit_sql = next(s for s in blocks if 'UPDATE welcome_kits' in s)
coupon_sql = next(s for s in blocks if 'UPDATE player_coupons' in s)
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
db.executescript('''
CREATE TABLE users(username TEXT PRIMARY KEY, password TEXT, enabled INTEGER);
CREATE TABLE authorities(username TEXT, authority TEXT);
INSERT INTO users VALUES ('official','hash',1),('player','hash',1),('other','hash',1);
INSERT INTO authorities VALUES ('player','ROLE_USER'),('other','ROLE_USER');
''')
db.executescript((root / 'src/main/resources/benefits-schema.sql').read_text())
db.execute("INSERT INTO benefit_events VALUES ('e','Event',50000,2,'Refreshment')")
def pay(id, ref, player='player', amount=50000):
    return db.execute(payment_sql, (id,ref,amount,'official','now',player,'e',amount)).rowcount
assert pay('p','r') == 1
db.execute("INSERT INTO welcome_kits(payment_id) VALUES ('p')")
db.execute("INSERT INTO player_coupons VALUES ('c','p','Refreshment',NULL,NULL)")
assert pay('p2','r') == 0
assert pay('p3','different') == 0
assert pay('p4','r','other') == 0
assert pay('p5','new','other',49999) == 0
assert pay('p6','new','official') == 0
assert db.execute(kit_sql, ('first','official','e','player')).rowcount == 1
assert db.execute(kit_sql, ('second','official','e','player')).rowcount == 0
assert db.execute(coupon_sql, ('first','official','c','e','other')).rowcount == 0
assert db.execute(coupon_sql, ('first','official','c','e','player')).rowcount == 1
assert db.execute(coupon_sql, ('second','official','c','e','player')).rowcount == 0
assert db.execute("SELECT redeemed_at FROM player_coupons WHERE code='c'").fetchone()[0] == 'first'
db.commit()
try:
    with db:
        assert pay('rollback','fresh','other') == 1
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
list_sql = next(s for s in blocks if 'SELECT u.username, u.enabled' in s)
rows = db.execute(list_sql, ('e',)).fetchall()
assert len(rows) == 2
by_user = {r[0]: r for r in rows}
assert by_user['player'][2] == 'p'
assert by_user['player'][6:8] == (1, 1)
assert by_user['other'][2] is None
assert by_user['other'][6:8] == (0, 0)
assert all(r[2] is None for r in db.execute(list_sql, ('different-event',)))
db.execute("INSERT INTO authorities VALUES ('player','ROLE_ADMIN')")
assert [r[0] for r in db.execute(list_sql, ('e',))] == ['other']
print('PASS: payment and benefits checks; admin listing paid/unpaid, counts, event scope, staff exclusion')
