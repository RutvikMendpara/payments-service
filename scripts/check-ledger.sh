#!/usr/bin/env bash
# Checks the ledger's books straight from Postgres. Exits non-zero if anything is off.
set -euo pipefail
cd "$(dirname "$0")/.."

q() { docker compose exec -T ledger-db psql -U ledger -d ledger_db -tAc "$1"; }

fail=0
check() {
  local name=$1 value=$2
  if [[ "$value" == "0" ]]; then
    echo "ok    $name"
  else
    echo "FAIL  $name ($value)"
    fail=1
  fi
}

check "every asset nets to zero across all accounts" \
  "$(q "select count(*) from (select a.asset from balances b join accounts a on a.id = b.account_id group by a.asset having sum(b.balance) <> 0) x")"
check "every transaction balances per asset" \
  "$(q "select count(*) from (select transaction_id, asset from ledger_entries group by 1, 2 having sum(case when direction = 'DEBIT' then amount else -amount end) <> 0) x")"
check "balances match the sum of entries" \
  "$(q "select count(*) from balances b left join (select account_id, sum(case when direction = 'CREDIT' then amount else -amount end) net from ledger_entries group by 1) e on e.account_id = b.account_id where b.balance <> coalesce(e.net, 0)")"
check "no customer balance is negative" \
  "$(q "select count(*) from balances b join accounts a on a.id = b.account_id where a.kind = 'CUSTOMER' and b.balance < 0")"
check "no trade was posted twice" \
  "$(q "select count(*) from (select reference from transactions where type = 'TRADE_SETTLEMENT' and status = 'POSTED' group by reference having count(*) > 1) x")"

exit $fail
