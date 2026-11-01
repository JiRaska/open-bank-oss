# DERIVED from docs/adr/0356-five-million-client-capacity-qualification.md `## Delivery check` by
# .github/scripts/gen-adr-delivery-checks.py — never hand-edit; edit the ADR and regenerate.
# Each command line's expectation is its trailing `# ...` comment, or the comment line above it.
# ready
test -f perf/profiles/cz-current-account-5m-v1.yaml && test -f .github/scripts/check-capacity-qualification.py && echo ready
