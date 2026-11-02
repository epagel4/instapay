#!/usr/bin/env bash
# Demonstrates the settlement saga against the running Compose stack:
#   1) settled transfer  2) rejected transfer  3) settlement-service down mid-saga -> compensation
# Usage: docker compose up -d --build && ./scripts/saga-demo.sh
set -euo pipefail
B=http://localhost:8080/api/v1
H='Content-Type: application/json'
key() { uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

ALICE=$(curl -s -H "$H" -H "Idempotency-Key: $(key)" -d '{"holderName":"Alice","holderCpf":"529.982.247-25"}' $B/accounts | field id)
curl -s -o /dev/null -H "$H" -H "Idempotency-Key: $(key)" -d '{"amount":"100.00"}' $B/accounts/$ALICE/deposits
balance() { curl -s $B/accounts/$ALICE | field balance; }
external() {
  curl -s -H "$H" -H "Idempotency-Key: $(key)" \
    -d "{\"debtorAccountId\":\"$ALICE\",\"creditor\":{\"ispb\":\"$1\",\"accountId\":\"$(key)\"},\"amount\":\"$2\"}" \
    $B/transfers | field id
}
status() { curl -s $B/transfers/$1 | field status; }
wait_status() { for _ in $(seq 1 40); do [ "$(status $1)" = "$2" ] && return; sleep 1; done; }
# After a rebuild the broker may still hold the old consumer: wait until the group is Stable again.
wait_settlement_consumer() {
  for _ in $(seq 1 60); do
    docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --describe --group settlement-service --state 2>/dev/null | grep -q Stable && return
    sleep 1
  done
}
wait_settlement_consumer

echo "Alice: $ALICE, balance $(balance)"

T1=$(external 00000002 30.00); wait_status $T1 COMPLETED
echo "1) to 00000002 (participant): $(status $T1); balance $(balance)"

T2=$(external 99999999 10.00); wait_status $T2 FAILED
echo "2) to 99999999 (unknown):     $(status $T2); balance $(balance)"

docker compose stop settlement-service >/dev/null
T3=$(external 00000003 20.00)
echo "3) settlement-service stopped; transfer $(status $T3), balance held $(balance)"
echo "   waiting for timeout compensation (expiresAt + grace ~15 s)..."
wait_status $T3 FAILED
echo "   -> $(status $T3) ($(curl -s $B/transfers/$T3 | field failureReason)); balance $(balance)"
docker compose start settlement-service >/dev/null
echo "   settlement-service restarted (it will reject the stale request as 'expired')"

# Fails (exit 1) on any wrong outcome, so CI can run this as the end-to-end test.
FAILURES=0
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: expected '$3', got '$2'"; FAILURES=1; }; }
expect "settled transfer"      "$(status $T1)" COMPLETED
expect "rejected transfer"     "$(status $T2)" FAILED
expect "timed-out transfer"    "$(status $T3)" FAILED
expect "timeout reason"        "$(curl -s $B/transfers/$T3 | field failureReason)" settlement.timeout
expect "balance (100-30)"      "$(balance)" 70.00
expect "ledger sums to zero"   "$(docker compose exec -T postgres psql -U instapay -d payment -tAc 'SELECT coalesce(sum(amount_cents),0) FROM posting')" 0
exit $FAILURES
