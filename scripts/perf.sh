#!/usr/bin/env bash
# Runs a Gatling profile against the running Compose stack and archives the HTML report.
# Usage: ./scripts/perf.sh <label> [load|spike] [rps] [duration-seconds]
#   e.g. ./scripts/perf.sh baseline load 500 120
set -uo pipefail
LABEL=${1:?label}; PROFILE=${2:-load}; RPS=${3:-500}; DURATION=${4:-120}
cd "$(dirname "$0")/.."
OUT=$(mktemp)
echo "load average before run: $(uptime | sed 's/.*averages*: //')"
./gradlew -q :performance-tests:gatlingRun --simulation com.instapay.perf.InternalTransferSimulation \
  -Dperf.profile="$PROFILE" -Dperf.rps="$RPS" -Dperf.duration="$DURATION" > "$OUT" 2>&1
STATUS=$?
REPORT=$(ls -td performance-tests/build/reports/gatling/internaltransfersimulation-* | head -1)
DEST=docs/performance/reports/$LABEL-$PROFILE
rm -rf "$DEST"; mkdir -p "$(dirname "$DEST")"; cp -R "$REPORT" "$DEST"; rm -f "$DEST/simulation.log"
# Last console summary block, kept next to the HTML for the written analysis.
{ echo "load average at end: $(uptime | sed 's/.*averages*: //')"
  awk '/Global Information/{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' "$OUT" | sed '/^Reports generated/,$d'
  grep '^Global:' "$OUT"; } > "$DEST/summary.txt"
cat "$DEST/summary.txt"
echo "report: $DEST/index.html"
exit $STATUS
