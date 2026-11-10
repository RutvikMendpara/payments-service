// Load test for POST /transfers against the compose stack.
//   k6 run scripts/transfers.k6.js                 random pairs across 200 accounts
//   k6 run -e HOT=1 scripts/transfers.k6.js        every transfer debits the same account
//   k6 run -e VUS=100 -e DURATION=2m scripts/transfers.k6.js
import http from 'k6/http';
import { check } from 'k6';

const LEDGER = __ENV.LEDGER_URL || 'http://localhost:8081';
const TOKEN_URI = __ENV.TOKEN_URI || 'http://localhost:8180/realms/payments/protocol/openid-connect/token';
const ACCOUNTS = 200;
const HOT = __ENV.HOT === '1';

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{name:POST /transfers}': ['p(99)<1000'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const token = http.post(TOKEN_URI, {
    grant_type: 'client_credentials',
    client_id: 'ops-cli',
    client_secret: 'ops-secret',
  }).json('access_token');
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  const run = Date.now();
  const ids = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const owner = `k6-${run}-${i}`;
    const account = http.post(`${LEDGER}/accounts`, JSON.stringify({ ownerId: owner, asset: 'INR' }), { headers });
    const id = account.json('id');
    http.post(`${LEDGER}/accounts/${id}/deposits`, JSON.stringify({ amount: 1e12 }),
      { headers: Object.assign({ 'Idempotency-Key': `k6-seed-${owner}` }, headers) });
    ids.push(id);
  }
  return { token, ids, run };
}

export default function (data) {
  const n = data.ids.length;
  const fromIdx = HOT ? 0 : Math.floor(Math.random() * n);
  let toIdx = 1 + Math.floor(Math.random() * (n - 1));
  if (!HOT && toIdx === fromIdx) toIdx = 0;

  const body = JSON.stringify({
    fromAccountId: data.ids[fromIdx],
    toAccountId: data.ids[toIdx],
    amount: 1 + Math.floor(Math.random() * 100000),
  });
  const res = http.post(`${LEDGER}/transfers`, body, {
    headers: {
      Authorization: `Bearer ${data.token}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-${data.run}-${__VU}-${__ITER}`,
    },
    tags: { name: 'POST /transfers' },
  });
  check(res, { 'transfer posted': (r) => r.status === 201 });
}
