# Leaderboard Service

A backend service for a global game leaderboard. Players submit scores; the service ranks every player by
their **best** score, breaks ties by **who reached that score first**, and answers "top 100", "what's my rank?"
and "who's around me?" in milliseconds.

- **PostgreSQL** is the source of truth: every player and every score submission.
- **Redis** holds the leaderboard as one sorted set, so every rank query is O(log N + M).
- If Redis loses its data, the app **rebuilds the leaderboard from PostgreSQL** on startup.

Java 21, Spring Boot 4.1, PostgreSQL 18, Redis 8, Flyway, Testcontainers, k6, GitHub Actions.
Design decisions and trade-offs: [DESIGN.md](DESIGN.md).

## Architecture

```mermaid
flowchart LR
    client["Game client"] -->|"HTTP / JSON"| app["Spring Boot app<br/>(stateless)"]
    app -->|"INSERT every submission<br/>SELECT usernames (one IN query)"| pg[("PostgreSQL<br/>source of truth")]
    app -->|"ZADD GT, ZREVRANK<br/>ZREVRANGE, ZSCORE"| redis[("Redis<br/>sorted set leaderboard:global")]
    pg -. "startup: if Redis is empty,<br/>rebuild in batches" .-> redis
```

A score submission is inserted into PostgreSQL, then `ZADD leaderboard:global GT <encoded score> <playerId>`
updates Redis only if it beats the player's best, and `ZSCORE` + `ZREVRANK` return the best score and rank.

## Running it

```bash
docker compose up --build
```

Starts the app on <http://localhost:8080> with PostgreSQL and Redis; Flyway creates the schema.
Health of both data stores: <http://localhost:8080/actuator/health>.

```bash
curl -s -X POST localhost:8080/api/v1/players -H 'Content-Type: application/json' -d '{"username":"alice"}'
# {"id":"56d1203a-1080-4d5f-b96c-f2e6149e9bdb","username":"alice"}
ALICE=56d1203a-1080-4d5f-b96c-f2e6149e9bdb

curl -s -X POST localhost:8080/api/v1/scores -H 'Content-Type: application/json' \
  -d "{\"playerId\":\"$ALICE\",\"score\":4200}"
# {"playerId":"56d1203a-...","bestScore":4200,"rank":1}

curl -s 'localhost:8080/api/v1/leaderboard/top?limit=10'
# [{"rank":1,"playerId":"56d1203a-...","username":"alice","score":4200}]

curl -s localhost:8080/api/v1/leaderboard/players/$ALICE
curl -s "localhost:8080/api/v1/leaderboard/players/$ALICE/around?range=2"

# Errors are RFC 7807 problem details:
curl -s -X POST localhost:8080/api/v1/players -H 'Content-Type: application/json' -d '{"username":"alice"}'
# {"detail":"Username 'alice' is already taken","instance":"/api/v1/players","status":409,"title":"Username taken"}
```

### API (under `/api/v1`)

| Method & path | Success | Errors |
|---|---|---|
| `POST /players` `{"username"}` | `201 {"id","username"}` | 400 invalid username (3-20 chars: letters, digits, `_`), 409 taken |
| `POST /scores` `{"playerId","score"}` | `201 {"playerId","bestScore","rank"}` | 400 invalid body or score outside 0-2,097,151, 404 unknown player |
| `GET /leaderboard/top?limit=100` | `200 [{"rank","playerId","username","score"}]` | 400 limit outside 1-100 |
| `GET /leaderboard/players/{playerId}` | `200 {"rank","playerId","username","score"}` | 404 player has no score |
| `GET /leaderboard/players/{playerId}/around?range=5` | `200 [...]` | 400 range outside 1-25, 404 player has no score |

## Tests

```bash
./mvnw verify
```

Runs the unit tests (`*Test`, JUnit 5 + Mockito), then the integration tests (`*IT`), which start real PostgreSQL
and Redis containers with Testcontainers. Only Docker needs to be running. The integration tests cover every
endpoint end to end, including ties, lower scores not changing the rank, "around" at the top and bottom of the
board, unknown players (404), a check that `GET /top` runs exactly one SQL query (no N+1), and the Redis rebuild
after `FLUSHALL`. CI ([ci.yml](.github/workflows/ci.yml)) runs the same command on every push and pull request.

> **Using Colima?** Testcontainers doesn't read Docker contexts, so point it at Colima's socket:
> `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`

## Tie-breaking: how scores are encoded in Redis

Redis orders a sorted set by one number (a 64-bit double); equal numbers fall back to the member name (the player
id), which is effectively random. To make "first to reach the score ranks higher" part of Redis's own ordering,
the score and the time are packed into one number:

```
encoded = score * 2^32 + (2^32 - 1 - secondsSince2025)
```

The score is in the high part, so a higher score always wins. The time is in the low part, inverted, so an
earlier time gives a larger number; it is always below 2^32, so it never carries into the score part. A later
submission of an equal score encodes lower, so `ZADD GT` keeps the earlier one.

**Limits.** A double holds integers exactly only up to 2^53, so score and time share 53 bits:

| Time precision | Time bits | Supported until | Max score |
|---|---|---|---|
| **1 second (chosen)** | **32** | **2161-02-07** | **2,097,151** |
| 1 second | 30 | 2059-01-10 | 8,388,607 |
| 1 millisecond | 42 | 2164-05-15 | 2,047 |

Scores above 2,097,151 are rejected with a 400. Players reaching the same score in the same second tie exactly,
and Redis then orders them by player id. Options for bigger scores are in
[DESIGN.md](DESIGN.md#5-tie-breaking-and-its-limits).

## Seed data and load test

```bash
docker compose up --build -d
./scripts/seed.sh            # 100,000 players with random scores (wipes existing data)
docker compose run --rm k6   # 60 s, 50 virtual users
```

`seed.sh` inserts directly into PostgreSQL, deletes the Redis leaderboard and restarts the app, which then
rebuilds it: **100,000 players in 255 ms** (20 batches of 5,000). [`load/leaderboard.js`](load/leaderboard.js)
sends ~70% reads (25% top 100, 25% player rank, 20% around +/-5) and ~30% score submissions, with no pause
between requests.

### Results

Against the seeded service, after a 20-second warm-up that isn't counted.
Raw output: [`load/results/k6-run.txt`](load/results/k6-run.txt).

**5,746 requests/s, p95 18.98 ms, p99 28.47 ms, 0.00% errors** (340,680 requests in 60 s).

| Endpoint | median | p95 | p99 |
|---|---|---|---|
| `GET /leaderboard/top?limit=100` | 5.56 ms | 16.08 ms | 24.34 ms |
| `GET /leaderboard/players/{id}` | 6.64 ms | 15.90 ms | 24.45 ms |
| `GET /leaderboard/players/{id}/around?range=5` | 8.80 ms | 19.18 ms | 28.35 ms |
| `POST /scores` | 9.99 ms | 22.05 ms | 32.76 ms |
| **All** | **7.48 ms** | **18.98 ms** | **28.47 ms** |

**Machine:** MacBook Pro, Apple M5 (10 cores), 24 GB RAM, macOS 26.6.2. Docker ran in a Colima VM limited to
**2 vCPUs and 4 GiB**, shared by the app, PostgreSQL, Redis and k6 itself, so treat these numbers as a floor.

## Project layout

```
src/main/java/com/example/leaderboard/
  player/        POST /players
  score/         POST /scores: stores submissions, updates the leaderboard
  leaderboard/   Redis sorted set, score encoding, read endpoints, startup rebuild
  common/        RFC 7807 error handling, Clock bean
src/main/resources/db/migration/   Flyway migrations
scripts/seed.sh, load/leaderboard.js
```
