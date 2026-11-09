# Design notes

Each section opens with a short answer, then goes into more detail.

1. [Why a Redis sorted set](#1-why-a-redis-sorted-set)
2. [What happens if Redis restarts or loses data](#2-what-happens-if-redis-restarts-or-loses-data)
3. [How PostgreSQL and Redis stay consistent](#3-how-postgresql-and-redis-stay-consistent)
4. [Scaling past one Redis node](#4-scaling-past-one-redis-node)
5. [Tie-breaking and its limits](#5-tie-breaking-and-its-limits)
6. [What I would add next](#6-what-i-would-add-next)

---

## 1. Why a Redis sorted set

**Short answer:** A leaderboard has to answer "what's my rank?" for any player, instantly. A sorted set keeps
members ordered by score *and* knows each member's position, so rank, top N and "players around me" are all
O(log N), plus the number of entries returned.

**Why not just SQL?** The top 100 is easy with an index (`ORDER BY best_score DESC LIMIT 100`). But a single
player's rank means counting everyone with a higher score: `SELECT count(*) … WHERE best_score > ?`. That is
O(N) per request. With 100,000 players at thousands of requests per second, that doesn't scale. A B-tree index
can find a row fast but can't tell you its position without counting.

**How Redis does it:** a sorted set is a hash map (member → score) plus a skip list ordered by score. Each
skip-list link stores how many elements it jumps over (its "span"), so Redis adds up spans while descending to
get a member's rank in O(log N).

| Operation | Command | Complexity | Used for |
|---|---|---|---|
| Submit a score | `ZADD key GT score member` | O(log N) | `POST /scores` |
| Rank of a player | `ZREVRANK key member` | O(log N) | rank, around |
| Score of a player | `ZSCORE key member` | O(1) (hash lookup) | rank, around |
| Range by position | `ZREVRANGE key start stop WITHSCORES` | O(log N + M) | top, around |
| Board size | `ZCARD key` | O(1) | startup rebuild check |
| Rebuild batch | `ZADD key GT s1 m1 … sM mM` | O(M log N) | startup rebuild |

N = players on the board, M = entries returned (≤ 100 for top, ≤ 51 for around).

**Measured:** 100,000 players use **11.2 MB** in Redis (~112 bytes each), so 10 million players would be roughly
1.1 GB, which fits in one node's memory.

Other choices worth explaining:
- **Member = player id, not username.** Ids never change and are fixed-size. Usernames are added from PostgreSQL
  in **one** `WHERE id IN (…)` query per response, not one query per row (no N+1). A test checks that exactly one
  SQL statement runs for `GET /top`.
- **`ZADD … GT` makes "keep the best score" atomic in Redis.** No read-compare-write in Java, so two concurrent
  submissions for the same player can't race and overwrite a high score with a lower one.
- **`ZREVRANGE` is marked deprecated in Redis 6.2+** in favour of `ZRANGE … REV`, but it still works and has the
  same complexity.

## 2. What happens if Redis restarts or loses data

**Short answer:** Nothing is lost, because Redis isn't the source of truth. PostgreSQL has every submission. On
startup, if the sorted set is empty, the app rebuilds it from PostgreSQL in batches before it reports itself
ready. In our test, 100,000 players were rebuilt in **255 ms**.

**How the rebuild works** (`LeaderboardRebuilder`):

1. Runs as a Spring Boot `ApplicationRunner`. Spring only marks the app *ready* after runners finish, so
   `/actuator/health/readiness` reports `OUT_OF_SERVICE` and a load balancer won't send traffic mid-rebuild.
2. `ZCARD leaderboard:global`: if it's not 0, skip.
3. Read each player's best submission, 5,000 players at a time:
   ```sql
   SELECT DISTINCT ON (player_id) player_id, score, submitted_at
   FROM score_submissions
   WHERE player_id > :lastSeenPlayerId
   ORDER BY player_id, score DESC, submitted_at ASC
   LIMIT 5000
   ```
   `DISTINCT ON` keeps the first row per player: the highest score, earliest time. The index
   `(player_id, score DESC, submitted_at ASC)` provides exactly that order. Paging by `player_id > last seen`
   (keyset pagination) costs the same for every batch; `OFFSET` would get slower as it skips more rows.
4. Write each batch with one `ZADD … GT` command. `GT` means a live submission that arrives during the rebuild
   can't be overwritten by an older, lower score. Both are "keep the max", so the order doesn't matter.
5. Log the number of players, batches and elapsed time.

Several app instances starting at once would each rebuild, which is harmless: `ZADD GT` is idempotent.

**Limits and how I'd improve them:**
- **Partial loss isn't detected.** The check is "is the set empty?". If Redis fails over to a replica that missed
  the last few writes, the board isn't empty, so no rebuild happens. Fix: a periodic reconciliation job that
  re-applies recent submissions (`ZADD GT` again for submissions in the last N minutes). It's idempotent, so
  it's safe to run often.
- **Rebuild time grows with players.** It's linear: ~2.5 ms per 1,000 players here, so ~25 s for 10 million.
  Turning on Redis persistence (AOF or RDB snapshots) makes a full rebuild rare. Our Docker Compose setup
  deliberately has no Redis volume to show that the rebuild works.
- **If Redis is down at startup**, the rebuild fails and the app doesn't start (fail fast). The orchestrator
  restarts it until Redis is back.

## 3. How PostgreSQL and Redis stay consistent

**Short answer:** They're two separate systems, so there's no transaction across both. I write PostgreSQL first
(the source of truth), then Redis. Every failure then leaves Redis *behind* PostgreSQL, never ahead of it. That
is easy to repair: retrying is safe because the leaderboard keeps the maximum, and the rebuild can always recreate
Redis from PostgreSQL.

What can go wrong between the two writes:

| Failure | Result | Recovery |
|---|---|---|
| PostgreSQL insert fails | Nothing written; client gets an error | Client retries |
| PostgreSQL OK, Redis write fails | Submission stored, board not updated; client gets a 500 | Client retries: a duplicate row is harmless and `ZADD GT` is idempotent |
| App crashes between the two writes | Same as above, but nobody is told | Rebuild (if Redis is empty) or a reconciliation job |
| Two submissions for one player at once | Both stored; Redis keeps the higher one (atomic `GT`) | None needed |
| Rank changes right after the response | The returned rank is a snapshot | Expected; reads show the latest |

**Why not the other order?** Writing Redis first could put a score on the board that was never saved (if
PostgreSQL then fails). That's a phantom score a rebuild would silently remove. Players would see their rank
drop for no reason.

**Ways to make it stronger:**
- **Transactional outbox:** in the same PostgreSQL transaction as the submission, insert a row into an `outbox`
  table. A background worker applies outbox rows to Redis and deletes them. No submission can be missed, even
  if the app crashes.
- **Change data capture** (e.g. Debezium reading the PostgreSQL WAL) does the same without app code.
- **Idempotency keys** on `POST /scores`, so client retries don't create duplicate submission rows.

## 4. Scaling past one Redis node

**Short answer:** First, one node goes a long way: ~1 GB for 10 million players and well over 100k simple
operations per second. Before sharding one board, I'd split it the way games naturally do (per season,
region or mode), add read replicas for read-heavy traffic, and cache the top 100. Sharding a *single* global
board is the last resort, because global rank then needs data from every shard.

**In order of what I'd try:**

1. **Scale the app horizontally.** It is stateless: run more instances behind a load balancer.
2. **Cache the top 100.** It's the most-read endpoint and the same for everyone. Even a 1-second cache cuts its
   Redis load to one query per second per instance.
3. **Read replicas.** Send `ZREVRANK`/`ZREVRANGE` to replicas and writes to the primary. Replication is
   asynchronous, so a player might briefly not see their new rank; read-your-own-writes can go to the primary.
4. **Many boards instead of one.** Per-season (`leaderboard:season:2026-11`), per-region (`leaderboard:eu`), per
   game mode. Each is a separate key, and **Redis Cluster** spreads keys across shards automatically. This is
   usually what games actually want.
5. **Sharding one huge board:**
   - *By score range* (shard 1: scores ≥ 1M, shard 2: 100k–1M, …): a player's global rank = (players in all higher
     shards, via `ZCARD`) + their rank in their own shard. Top N reads only the top shard. Shards become uneven as
     scores grow, so ranges need rebalancing.
   - *By player hash*: writes spread evenly, but every rank query must ask all S shards "how many players are above
     this score?" (`ZCOUNT`), so O(S log N) per read, and top N needs a merge of each shard's top N.
   - *Approximate ranks:* beyond the top few thousand, players rarely need an exact rank. "Top 3%" can come from a
     score histogram that is cheap to keep, while the exact top-K stays in a sorted set.

## 5. Tie-breaking and its limits

**Short answer:** Redis sorts by one number, so I pack the score and the time into it:
`score × 2³² + (2³² − 1 − secondsSince2025)`. The score takes the high bits, so higher scores always win. The
time takes the low bits, inverted, so earlier times win ties. Redis stores scores as doubles, which hold integers
exactly only up to 2⁵³. That gives 21 bits for the score (max **2,097,151**) and 32 bits for time (one-second
precision, valid until **2161**).

Details:
- `ZADD GT` and tie-breaking work together: re-submitting the same score later encodes *lower*, so it doesn't
  replace the player's earlier, better-placed entry.
- The time is the **server's** time when the submission arrives, not a client-supplied time, which could be faked.
- `submittedAt` is truncated to microseconds before saving (PostgreSQL's precision), so the time encoded live is
  exactly what the rebuild later reads back. A rebuilt board orders ties the same way. A test flushes Redis,
  rebuilds and compares the board byte for byte.

**Limits:**
- **Max score 2,097,151.** Higher scores are rejected with a 400 rather than silently mis-ranked.
- **Same-second ties** encode identically; Redis then orders those players by player id (stable but arbitrary).
- **Clock skew between app instances** can misorder two submissions a few milliseconds apart. Clocks synced with
  NTP are well within one second.

**If a game needed bigger scores:** use coarser time (minutes: 2²⁵ minutes ≈ 64 years leaves 28 bits, max 268M),
or keep the plain score in the sorted set and resolve ties in a second step. For example, store the
`(score, time)` order in PostgreSQL and only consult it for the few players with an equal score.

## 6. What I would add next

**Seasonal leaderboards.** One key per season (`leaderboard:season:2026-11`) and a `season_id` column on
submissions. `EXPIREAT` removes old seasons from Redis after the season ends; final standings are copied to a
PostgreSQL `season_results` table first, so history is permanent. The global all-time board stays as it is.

**Friends leaderboards.** A player has at most a few hundred friends, so: `ZMSCORE leaderboard:global f1 f2 …`
(one command, O(F)), then sort those F scores in the app. That's simpler and cheaper than maintaining a sorted
set per player. For "my rank among friends", count how many friends' scores are above mine.

**Anti-cheat score validation.**
- Never trust the client's score blindly. Ideally the server computes the result, or the client sends a signed
  match summary the server can verify.
- Plausibility checks: maximum score per second of play, match duration, impossible jumps compared with the
  player's history.
- Rate limiting per player and per IP on `POST /scores`.
- A `flagged` status: suspicious scores are kept in PostgreSQL for review but not added to the public board
  (or "shadow-banned", so the cheater only sees themselves).

**Caching the top 100.** The same response goes to everyone. Cache the serialized JSON in memory with a 1-second
TTL (e.g. Caffeine). Readers see results at most 1 s old, and Redis gets one `ZREVRANGE` per second per instance
instead of thousands. To be more precise, a submission could invalidate the cache only if its score beats the
current 100th place.

**Also on the list:** authentication (players can only submit their own scores); combining the 3 Redis round
trips in `POST /scores` into one with pipelining or a Lua script (`ZREVRANK … WITHSCORE`, Redis 7.2+, returns rank
and score together); metrics and tracing (Micrometer); case-insensitive usernames (a unique index on
`lower(username)`); and the outbox and reconciliation job from sections 2 and 3.
