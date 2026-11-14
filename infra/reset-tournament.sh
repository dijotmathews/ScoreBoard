#!/bin/bash
# Clears the live database for a new tournament: every event (draws and matches), every
# player benefit (payments, welcome kits, coupons and the benefit settings) and every player
# account. Staff accounts (any account with a role other than ROLE_USER) are kept.
#
# app.db is backed up first, to s3://<backup bucket>/manual/ (outside Litestream's path, so
# its retention does not delete it) and to a local folder. The app is stopped while this
# runs, about a minute. Runs on the server through SSM; needs AWS credentials
# (aws sso login --profile scoreboard) and an initialised terraform (see README.md).
#
# Usage: ./reset-tournament.sh [local backup folder, default ~/scoreboard-backups]
set -euo pipefail
cd "$(dirname "$0")"
local_dir=${1:-$HOME/scoreboard-backups}

domain=$(terraform output -raw url | sed 's|https://||')
region=$(terraform output -raw region)
instance=$(terraform output -raw instance_id)
bucket=$(terraform output -raw backup_bucket)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_key="manual/app-before-reset-$stamp.db"

cat <<EOF
This deletes from the live database at https://$domain:
  - all events, draws and matches
  - all payments, welcome kits, coupons and the benefit settings
  - all player accounts (staff accounts are kept)
app.db is first backed up to s3://$bucket/$backup_key and $local_dir.

EOF
read -r -p "Type the domain ($domain) to continue: " answer
[ "$answer" = "$domain" ] || { echo "Cancelled."; exit 1; }

# Runs on the server as root. Any failure before COMMIT leaves app.db unchanged, and the
# app is started again however the script ends.
remote=$(cat <<'REMOTE'
set -euo pipefail
cd /opt/scoreboard
trap 'chown 10001:10001 /data/app.db*; docker compose start app' EXIT
command -v sqlite3 > /dev/null || dnf install -y -q sqlite
docker compose stop app

backup=/data/app-before-reset.db
rm -f "$backup"*
sqlite3 /data/app.db ".backup $backup"
[ "$(sqlite3 "$backup" 'PRAGMA integrity_check;')" = ok ]
aws s3 cp --quiet --region __REGION__ "$backup" s3://__BUCKET__/__KEY__
echo "Backed up: s3://__BUCKET__/__KEY__"
rm -f "$backup"*

sqlite3 -bail /data/app.db <<'SQL'
PRAGMA foreign_keys = ON;
BEGIN;
DELETE FROM matches;
DELETE FROM draw_slots;
DELETE FROM events;
DELETE FROM player_coupons;
DELETE FROM welcome_kits;
DELETE FROM entry_payments;
DELETE FROM benefit_settings;
CREATE TEMP TABLE players AS
  SELECT u.username FROM users u
  WHERE EXISTS (SELECT 1 FROM authorities a
                WHERE a.username = u.username AND a.authority = 'ROLE_USER')
    AND NOT EXISTS (SELECT 1 FROM authorities a
                    WHERE a.username = u.username AND a.authority <> 'ROLE_USER');
DELETE FROM authorities WHERE username IN (SELECT username FROM players);
DELETE FROM users WHERE username IN (SELECT username FROM players);
COMMIT;
SQL

[ -z "$(sqlite3 /data/app.db 'PRAGMA foreign_key_check;')" ]
echo "Accounts left:"
sqlite3 /data/app.db "SELECT '  ' || u.username || ' ' || group_concat(a.authority)
  FROM users u LEFT JOIN authorities a USING(username) GROUP BY u.username;"

chown 10001:10001 /data/app.db*
docker compose start app
trap - EXIT
for _ in $(seq 1 40); do
  curl -fsS -o /dev/null http://localhost/login 2> /dev/null && { echo "App is up."; exit 0; }
  sleep 3
done
echo "App did not answer on /login; check: docker compose logs app" >&2
exit 1
REMOTE
)
remote=${remote//__REGION__/$region}
remote=${remote//__BUCKET__/$bucket}
remote=${remote//__KEY__/$backup_key}

params=$(python3 -c 'import json, sys; print(json.dumps({"commands": [sys.stdin.read()]}))' <<< "$remote")
command_id=$(aws ssm send-command --region "$region" --instance-ids "$instance" \
  --document-name AWS-RunShellScript --comment "ScoreBoard tournament reset" \
  --parameters "$params" --query Command.CommandId --output text)

echo "Running on $instance (SSM command $command_id)..."
status=Pending
for _ in $(seq 1 100); do
  status=$(aws ssm get-command-invocation --region "$region" --command-id "$command_id" \
    --instance-id "$instance" --query Status --output text 2> /dev/null || echo Pending)
  case "$status" in Pending | InProgress | Delayed) sleep 3 ;; *) break ;; esac
done
aws ssm get-command-invocation --region "$region" --command-id "$command_id" \
  --instance-id "$instance" --query '[StandardOutputContent, StandardErrorContent]' --output text
[ "$status" = Success ] || { echo "Reset failed ($status)." >&2; exit 1; }

# The S3 upload happened before anything was deleted, so this copy is the pre-reset app.db.
mkdir -p "$local_dir"
aws s3 cp --quiet --region "$region" "s3://$bucket/$backup_key" "$local_dir/"

cat <<EOF

Done. Backups of the old database:
  s3://$bucket/$backup_key
  $local_dir/$(basename "$backup_key")
Next: sign in as admin and set the entry fee and coupons under Benefit settings.

Restore the old database if needed (on the server, through SSM or a session):
  cd /opt/scoreboard && docker compose stop app litestream
  aws s3 cp s3://$bucket/$backup_key /data/app.db && rm -f /data/app.db-wal /data/app.db-shm
  chown 10001:10001 /data/app.db && docker compose start app litestream
EOF
