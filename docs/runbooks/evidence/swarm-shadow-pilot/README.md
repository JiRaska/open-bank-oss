# Swarm shadow pilot evidence

Operational evidence store for the ADR-0244 Phase 5 shadow incident-response
swarm pilot. Each daily run is captured as `YYYY-MM-DD.md`.

## Exit criteria (from #5839)

- [ ] Authentication binding fix from #4985 exercised in pilot traffic.
- [ ] OPA-gated signals, case budgets, circuit breaker and kill-switch
  cancellation observed in real/close-to-real cases.
- [ ] At least 50 cases recorded or explicit shortfall documented.
- [ ] Convergence/dissent evaluated using the ADR-0148 gate.
- [ ] Human owner signs expose/extend decision; money-path classes excluded.

## Daily drill schedule

| Day | Date       | Kill-switch drill | Cases observed | Evidence |
|-----|------------|-------------------|----------------|----------|
| 1   | 2026-09-23 | pending           | 0+             | [2026-09-23.md](./2026-09-23.md) |

## References

- ADR-0244
- #5839
- #10607
