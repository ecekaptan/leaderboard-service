// k6 load test: ~70% reads (top, player rank, around) and ~30% score submissions.
//
// Run against the seeded Docker Compose stack:
//   ./scripts/seed.sh
//   docker compose run --rm k6
//
// Or with a local k6 install:  k6 run -e BASE_URL=http://localhost:8080 load/leaderboard.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '60s';

// Player ids written by scripts/seed.sh. SharedArray loads the file once and shares it between VUs.
const playerIds = new SharedArray('player ids', () => JSON.parse(open('./player-ids.json')));

export const options = {
  scenarios: {
    // A fixed number of virtual users sending requests back to back (no think time):
    // this measures how much throughput the service sustains, and the latency at that load.
    mixed: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // One threshold per endpoint also makes k6 print a latency breakdown per endpoint.
    'http_req_duration{endpoint:top}': ['p(95)<500'],
    'http_req_duration{endpoint:rank}': ['p(95)<500'],
    'http_req_duration{endpoint:around}': ['p(95)<500'],
    'http_req_duration{endpoint:submit}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (playerIds.length === 0) {
    throw new Error('load/player-ids.json is empty: run ./scripts/seed.sh first');
  }
}

function randomPlayerId() {
  return playerIds[Math.floor(Math.random() * playerIds.length)];
}

// `name` groups all URLs of one endpoint into one metric series (otherwise every player id is its own series).
function params(endpoint, name) {
  return { tags: { endpoint: endpoint, name: name } };
}

export default function () {
  const roll = Math.random();

  if (roll < 0.25) {
    const res = http.get(`${BASE_URL}/api/v1/leaderboard/top?limit=100`,
      params('top', 'GET /leaderboard/top'));
    check(res, { 'top: 200': (r) => r.status === 200 });
  } else if (roll < 0.50) {
    const res = http.get(`${BASE_URL}/api/v1/leaderboard/players/${randomPlayerId()}`,
      params('rank', 'GET /leaderboard/players/{id}'));
    check(res, { 'rank: 200': (r) => r.status === 200 });
  } else if (roll < 0.70) {
    const res = http.get(`${BASE_URL}/api/v1/leaderboard/players/${randomPlayerId()}/around?range=5`,
      params('around', 'GET /leaderboard/players/{id}/around'));
    check(res, { 'around: 200': (r) => r.status === 200 });
  } else {
    const body = JSON.stringify({ playerId: randomPlayerId(), score: Math.floor(Math.random() * 1000000) });
    const res = http.post(`${BASE_URL}/api/v1/scores`, body, {
      headers: { 'Content-Type': 'application/json' },
      ...params('submit', 'POST /scores'),
    });
    check(res, { 'submit: 201': (r) => r.status === 201 });
  }
}
