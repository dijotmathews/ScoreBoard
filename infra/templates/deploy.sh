#!/bin/bash
# Usage: deploy.sh [image-tag]
# Runs the given image (or, without a tag, the last deployed one) and waits until it
# answers. If the new image fails, the previous one is started again.
set -euo pipefail
cd /opt/scoreboard
source ./config.env

# Kept on the data volume, so a replaced server starts the same image.
TAG_FILE=/data/.deployed-tag
previous=$(cat "$TAG_FILE" 2>/dev/null || true)
tag=${1:-$previous}
if [ -z "$tag" ]; then
  echo "No image deployed yet; push to main to deploy one."
  exit 0
fi

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
admin_password=$(aws ssm get-parameter --region "$REGION" --name "$ADMIN_PARAMETER" \
  --with-decryption --query Parameter.Value --output text)

start() {
  umask 077
  cat > .env <<EOF
IMAGE='$IMAGE_REPOSITORY:$1'
APP_BOOTSTRAP_ADMIN_PASSWORD='$admin_password'
REGION='$REGION'
LOG_GROUP='$LOG_GROUP'
EOF
  docker compose pull --quiet
  docker compose up -d --remove-orphans
}

healthy() {
  for _ in $(seq 1 60); do
    curl -fsS -o /dev/null http://localhost/login && return 0
    sleep 3
  done
  return 1
}

echo "Deploying $tag (previous: ${previous:-none})"
start "$tag"
if healthy; then
  echo "$tag" > "$TAG_FILE"
  docker image prune -af --filter "until=168h" > /dev/null || true
  echo "Deployed $tag"
  exit 0
fi

echo "$tag did not become healthy; recent logs:" >&2
docker compose logs --tail 50 app >&2 || true
if [ -n "$previous" ] && [ "$previous" != "$tag" ]; then
  echo "Rolling back to $previous" >&2
  start "$previous"
  healthy || echo "Rollback to $previous is not healthy either" >&2
fi
exit 1
