# DERIVED from docs/adr/0317-split-openbank-libs-domain-into-a-platform-core-and-per-bounded-context-libs-modules.md `## Delivery check` by
# .github/scripts/gen-adr-delivery-checks.py — never hand-edit; edit the ADR and regenerate.
# Each command line's expectation is its trailing `# ...` comment, or the comment line above it.
# (1) no business package left in core — expect empty
git grep -lE '^package com\.openbank\.libs\.(lending|decision|iso20022|llm|reasoning|analytics|synthetic|domain\.(feature|payment|case|cards))' -- openbank-libs-domain/src/main
# (2) a lending-module change rebuilds only its declarers — expect 3
grep -lE '^\s*(implementation|api)\(project\(":openbank-libs-lending"\)\)' openbank-*/build.gradle.kts | wc -l
# (3) gate present and enforced
grep -A6 'id: libs-core-purity' .github/gates/gates.yaml | grep 'mode: enforced'
