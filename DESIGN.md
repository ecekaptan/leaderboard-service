# Design notes

1. [Why a Redis sorted set](#1-why-a-redis-sorted-set)
2. [What happens if Redis restarts or loses data](#2-what-happens-if-redis-restarts-or-loses-data)
3. [How PostgreSQL and Redis stay consistent](#3-how-postgresql-and-redis-stay-consistent)
4. [Scaling past one Redis node](#4-scaling-past-one-redis-node)
5. [Tie-breaking and its limits](#5-tie-breaking-and-its-limits)
6. [What I would add next](#6-what-i-would-add-next)

## 1. Why a Redis sorted set

**Short answer:** A leaderboard must answer "what's my rank?" for any player, instantly. A sorted set keeps
members ordered by score and knows each member's position, so rank, top N and "around me" are all O(log N) plus
the number of entries returned.

**Why not SQL?** Top 100 is easy with an index, but one player's rank means counting everyone above them
(`SELECT count(*) ... WHERE best_score > ?`): O(N) per request. A B-tree finds a row fast but can't tell its
position without counting. A Redis sorted set is a hash map (member -> score) plus a skip list whose links store
how many elements they skip, so Redis sums those while descending to get a rank in O(log N).

| Command | Complexity | Used for |
|---|---|---|
| `ZADD key GT score member` | O(log N) | `POST /scores` |
| `ZREVRANK key member` | O(log N) | rank, around |
| `ZSCORE key member` | O(1) | rank, around |
| `ZREVRANGE key start stop WITHSCORES` | O(log N + M) | top, around |
| `ZCARD key` | O(1) | startup rebuild check |
| `ZADD key GT` with M members | O(M log N) | rebuild batch |

N = players on the board, M = entries returned (<= 100 for top, <= 51 for around).
Measured: 100,000 players use **11.2 MB** (~112 bytes each), so 10 million would be ~1.1 GB.

- **Member = player id**, not username: ids never change. Usernames come from PostgreSQL in **one**
  `WHERE id IN (...)` query per response (no N+1); a test asserts exactly one SQL statement for `GET /top`.
- **`ZADD GT` makes "keep the best score" atomic** inside Redis: no read-compare-write in Java, so concurrent
  submissions can't overwrite a high score with a lower one.

## 2. What happens if Redis restarts or loses data

**Short answer:** Nothing is lost: PostgreSQL has every submission. On startup, if the sorted set is empty, the
app rebuilds it from PostgreSQL in batches before reporting ready. 100,000 players took **255 ms**.

How `LeaderboardRebuilder` works:

1. It's an `ApplicationRunner`. Spring marks the app ready only after runners finish, so readiness stays
   `OUT_OF_SERVICE` and a load balancer won't route traffic mid-rebuild.
2. If `ZCARD` is not 0, skip. Otherwise read each player's best submission, 5,000 players per batch:
   ```sql
   SELECT DISTINCT ON (player_id) player_id, score, submitted_at
   FROM score_submissions
   WHERE player_id > :lastSeenPlayerId
   ORDER BY player_id, score DESC, submitted_at ASC
   LIMIT 5000
   ```
   `DISTINCT ON` keeps the first row per player (highest score, earliest time), in the order the
   `(player_id, score DESC, submitted_at ASC)` index already provides. Keyset paging (`player_id > last seen`)
   costs the same for every batch, unlike `OFFSET`.
3. Write each batch with one `ZADD GT`, so live submissions arriving mid-rebuild are never overwritten by an
   older, lower score. Several instances rebuilding at once is harmless for the same reason.
4. Log players, batches and elapsed time.

**Limits:**
- **Partial loss isn't detected**, since the check is "is it empty?" (e.g. failover to a replica that missed
  recent writes). Fix: a periodic job that re-applies recent submissions with `ZADD GT`. It's idempotent.
- **Rebuild time is linear**: ~2.5 ms per 1,000 players here, so roughly 25 s for 10 million. Redis persistence
  (AOF/RDB) makes full rebuilds rare; Docker Compose has no Redis volume on purpose, to show the rebuild.
- **If Redis is down at startup**, the app fails to start, and the orchestrator restarts it until Redis is back.

## 3. How PostgreSQL and Redis stay consistent

**Short answer:** There's no transaction across both, so I write PostgreSQL (the source of truth) first, then
Redis. Any failure leaves Redis behind PostgreSQL, never ahead. That's easy to repair: retries are safe because the
board keeps the maximum, and the rebuild can always recreate Redis.

| Failure | Result | Recovery |
|---|---|---|
| PostgreSQL insert fails | Nothing written; client gets an error | Client retries |
| PostgreSQL OK, Redis fails | Submission stored, board not updated; client gets a 500 | Client retries; duplicate row is harmless, `ZADD GT` is idempotent |
| App crashes between the writes | Same, but nobody is told | Rebuild (if empty) or the reconciliation job |
| Two submissions for one player at once | Both stored; Redis keeps the higher | None needed |
| Rank changes after the response | Returned rank is a snapshot | Expected |

Writing Redis first instead could show a score that was never saved, which a later rebuild would silently
remove.

To make it stronger: a **transactional outbox** (insert an outbox row in the same transaction; a worker applies
it to Redis, so nothing is missed even on a crash), or **change data capture** (e.g. Debezium on the WAL), plus
**idempotency keys** on `POST /scores` so retries don't create duplicate rows.

## 4. Scaling past one Redis node

**Short answer:** One node goes a long way: ~1 GB for 10 million players and over 100k simple operations per
second. Before sharding a board, I'd split it the way games naturally do (season, region, mode), add read
replicas and cache the top 100. Sharding one global board is the last resort, because a global rank then needs
every shard.

In order:

1. **Scale the app horizontally.** It's stateless.
2. **Cache the top 100.** It's the most-read response and the same for everyone (see section 6).
3. **Read replicas** for `ZREVRANK`/`ZREVRANGE`. Replication is async, so a player's own reads can go to the
   primary.
4. **Many boards**: `leaderboard:season:2026-11`, `leaderboard:eu`, per game mode. Separate keys spread
   across shards automatically with **Redis Cluster**.
5. **Sharding one huge board:**
   - By **score range**: global rank = sum of `ZCARD` of higher shards + rank in own shard; top N reads one
     shard. Ranges need rebalancing as scores grow.
   - By **player hash**: even writes, but each rank asks all S shards for `ZCOUNT` above the score
     (O(S log N)), and top N merges every shard's top N.
   - **Approximate ranks** below the top few thousand ("top 3%") from a cheap score histogram.

## 5. Tie-breaking and its limits

**Short answer:** Redis sorts by one number, so I pack score and time into it:
`score * 2^32 + (2^32 - 1 - secondsSince2025)`. The score in the high bits means higher scores always win; the
inverted time in the low bits means earlier times win ties. Doubles are exact only up to 2^53, which leaves
21 bits for the score (max **2,097,151**) and 32 bits for one-second time (until **2161**). The full explanation
and alternatives are in the [README](README.md#tie-breaking-how-scores-are-encoded-in-redis).

- It works with `ZADD GT`: re-submitting the same score later encodes lower, so the earlier entry stays.
- The time is the **server's** arrival time; a client-supplied time could be faked.
- `submittedAt` is truncated to microseconds (PostgreSQL's precision), so a rebuilt board orders ties exactly
  like the live one. A test flushes Redis, rebuilds and compares the board byte for byte.

**Limits:** scores above the max get a 400 rather than a wrong rank; same-second ties fall back to player-id
order; clock skew between instances can misorder submissions milliseconds apart (NTP keeps it well under 1 s).

**For bigger scores:** use coarser time (minutes: 2^25 minutes is ~64 years, leaving 28 bits, max ~268M), or
keep the plain score in Redis and resolve equal scores from the `(score, time)` order in PostgreSQL.

## 6. What I would add next

- **Seasonal leaderboards:** one key per season plus a `season_id` column. Copy final standings to a PostgreSQL
  `season_results` table, then `EXPIREAT` the Redis key.
- **Friends leaderboards:** `ZMSCORE leaderboard:global f1 f2 ...` (one command, O(F)) and sort the few hundred
  results in the app. Cheaper than a sorted set per player.
- **Anti-cheat validation:** server-computed or signed match results; plausibility checks (score per second
  played, jumps versus the player's history); rate limits per player and IP; a `flagged` status that keeps
  suspicious scores in PostgreSQL for review but off the public board.
- **Caching the top 100:** in-memory JSON with a 1-second TTL (e.g. Caffeine) means one `ZREVRANGE` per second
  per instance instead of thousands. Optionally invalidate early when a score beats the current 100th place.
- **Also:** authentication; one Redis round trip for `POST /scores` (pipelining, a Lua script, or
  `ZREVRANK ... WITHSCORE` in Redis 7.2+); Micrometer metrics and tracing; case-insensitive usernames (unique
  index on `lower(username)`); the outbox and reconciliation job from sections 2 and 3.
