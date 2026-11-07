# DERIVED from docs/adr/0344-treasury-instrument-model-multi-leg-schedules-fixings-and-the-cash-flow-ledger.md `## Delivery check` by
# .github/scripts/gen-adr-delivery-checks.py — never hand-edit; edit the ADR and regenerate.
# Each command line's expectation is its trailing `# ...` comment, or the comment line above it.
# D1: no service carries its own day-count or calendar logic
git grep -lE 'object DayCount|fun isWeekend\(|fun nextBusinessDay\(' -- '*/src/main' | grep -v openbank-libs-domain   # prints nothing
# D2: the product set is complete
grep -cE 'IRS|FRA|FX_FORWARD|FX_SWAP|REPO|BOND|TREASURY_BILL|BILL_OF_EXCHANGE' openbank-treasury-service/src/main/kotlin/com/openbank/treasury/domain/model/Deal.kt   # >= 8
# D4: the cash-flow ledger exists and is overridable
grep -E '^  /api/v1/treasury/deals/\{id\}/cash-flows' openbank-treasury-service/src/main/resources/openapi.yaml   # path present, with an override operation
# D6: a payment instruction leaves the deal
git grep -lE 'treasury:.*:payment' -- openbank-treasury-service/src/main   # at least one file
# D9: the parity gate runs on PRs
yq '.gates[] | select(.id=="treasury-parity-fixtures") | .mode' .github/gates/gates.yaml   # enforced
ls openbank-treasury-service/src/test/resources/parity/*.json | wc -l   # >= number of product types
