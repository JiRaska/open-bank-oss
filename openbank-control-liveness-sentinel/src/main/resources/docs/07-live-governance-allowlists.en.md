# Live governance allowlists

`GovernanceReadAdapter` reads two governance allowlists from the fleet's live
`rules.yaml` (via the same mount → URL → mirror chain as
`GovernanceRulesReadAdapter`, ADR-0164) instead of carrying compiled-in copies:

- `event_consumer_liveness/topic` — the Kafka topics whose consumer liveness
  the sentinel is expected to watch.
- `lineage_code_audit/edge` — the code-audit edges in scope.

Both lists fail open: an unreachable or unparsable source yields an empty
allowlist (the sentinel reports liveness absence rather than inventing
subjects), and the divergence is logged at WARN. A service entering or leaving
one of these lists is now a governance-rules change, not a code change here —
which is the point: the compiled mirror and the rules file could no longer
silently disagree.
