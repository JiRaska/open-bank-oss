# Negative control for `agent-pr-guard` — DO NOT MERGE

This file exists to prove one thing: that `agent-pr-guard`'s RED is reachable in real CI, and
not only inside its own self-test. A gate that has only ever passed is unfalsified.

It sits under `openbank-ledger-service/` (a money-path service) on a branch named
`agent/guard-negative-control` (the declared agent prefix), which is exactly the combination
the guard must refuse. `Validate manifests` is expected to FAIL on this PR.

The PR carrying it is closed as soon as that failure is observed, and this file never reaches
`main`.
