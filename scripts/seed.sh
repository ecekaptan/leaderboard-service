#!/usr/bin/env bash
#
# Seeds the Docker Compose stack with players that each have one random score.
#
#   ./scripts/seed.sh          # 100,000 players
#   ./scripts/seed.sh 5000     # any number up to 999,999
#
# WARNING: deletes all existing players and scores first.
#
# Rows are inserted straight into PostgreSQL (one SQL statement each for players and scores, which takes
# seconds instead of 200,000 API calls). Then the Redis leaderboard is deleted and the app restarted, so
# the app rebuilds the leaderboard from PostgreSQL on startup, exercising the same recovery path it
# would use after Redis loses its data. Finally, a sample of player ids is written to load/player-ids.json
# for the k6 load test.
set -euo pipefail

PLAYERS="${1:-100000}"
if ! [[ "$PLAYERS" =~ ^[0-9]+$ ]] || (( PLAYERS < 1 || PLAYERS > 999999 )); then
  echo "Usage: $0 [players: 1-999999]" >&2
  exit 1
fi

cd "$(dirname "$0")/.."

echo "Seeding $PLAYERS players with random scores..."
# psql replaces :players with the -v value. Scores are 0-999,999; submission times are random within
# the last 30 days, at whole seconds, so some players tie and the earlier one ranks higher.
docker compose exec -T postgres psql -U leaderboard -d leaderboard -v ON_ERROR_STOP=1 -v players="$PLAYERS" <<'SQL'
BEGIN;
TRUNCATE TABLE score_submissions, players;

INSERT INTO players (id, username, created_at)
SELECT gen_random_uuid(), 'player_' || lpad(n::text, 6, '0'), now() - interval '31 days'
FROM generate_series(1, :players) AS n;

INSERT INTO score_submissions (player_id, score, submitted_at)
SELECT id,
       floor(random() * 1000000)::bigint,
       date_trunc('second', now() - random() * interval '30 days')
FROM players;

COMMIT;
ANALYZE players;
ANALYZE score_submissions;
SQL

echo "Deleting the Redis leaderboard and restarting the app (it rebuilds from PostgreSQL)..."
docker compose exec -T redis redis-cli DEL leaderboard:global > /dev/null
docker compose restart app > /dev/null

echo -n "Waiting for the app to be ready"
for _ in $(seq 1 120); do
  if curl -fs http://localhost:8080/actuator/health/readiness > /dev/null 2>&1; then
    echo " done."
    break
  fi
  echo -n "."
  sleep 1
done
curl -fs http://localhost:8080/actuator/health/readiness > /dev/null || { echo " app did not become ready" >&2; exit 1; }

docker compose logs app | grep "LeaderboardRebuilder" | tail -n 1

echo "Writing a sample of 10,000 player ids to load/player-ids.json for k6..."
docker compose exec -T postgres psql -U leaderboard -d leaderboard -At -c \
  "SELECT coalesce(json_agg(id), '[]') FROM (SELECT id FROM players ORDER BY random() LIMIT 10000) AS sample" \
  > load/player-ids.json

echo "Seeded $PLAYERS players."
