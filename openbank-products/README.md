<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# openbank-products — Product as Code (ADR-0352)

The product **content** module: reference deposit products authored in a build-time Kotlin DSL,
compiled to **IR v1 JSON** (`ir/<name>.json`, the artifact and the only thing the catalog stores),
golden scenarios that double as public conformance vectors, property tests over `ProductArb`, and
the `obp` CLI. A released component (`version.txt`): every product change is a release.

```
./gradlew :openbank-products:run --args="lint savings-fixed"                 # schema + run limits + ruleId uniqueness
./gradlew :openbank-products:run --args="compile all --out ir"               # DSL -> canonical IR (commit the result)
./gradlew :openbank-products:run --args="compile all --check"                # red when ir/ drifts from the DSL
./gradlew :openbank-products:run --args="compile my-draft.json"              # raw canonical IR on stdout, revisionHash on stderr
./gradlew :openbank-products:run --args="compile my-draft.json --json"       # {ok, revisionHash, ir}: the MCP product_compile report
./gradlew :openbank-products:run --args="test"                               # golden scenarios, shared runner
./gradlew :openbank-products:run --args="simulate savings-fixed --portfolio portfolios/savings-sample.json --from 2026-01-01T00:00:00Z --to 2026-02-01T00:00:00Z"
./gradlew :openbank-products:run --args="explain savings-fixed --rule scheduled.fee"
./gradlew :openbank-products:run --args='explain savings-fixed --instruction {"ruleId":"fee.maintenance"}'
./gradlew :openbank-products:run --args="sign savings-fixed --key-ref test:dev --revision-id <uuid> --revision-number 1"
./gradlew :openbank-products:run --args="propose savings-fixed --offering <uuid> --schema-id <id> --schema-version 1"
./gradlew :openbank-products:run --args="diff ir/savings-fixed.json my-reprice.json --behavioural --portfolio portfolios/savings-sample.json"
```

| Exit | Meaning |
|---|---|
| 0 | success |
| 1 | the subject is invalid, or a check (`compile --check`, `test`, `explain`) failed |
| 2 | refused: a non-test signing key |
| 64 | usage error |
| 65 | an input file or JSON argument (portfolio, instruction) is missing or malformed — one canonical `{"ok":false,"inputError":…}` line on stderr |
| 69 | `propose --execute`: the catalog was unreachable, egress was refused, or it did not answer 201 |
| 70 | internal error: an exception thrown during evaluation — a defect in obp or the interpreter, printed with its stack trace, never reported as bad input |

Failure output is one canonical JSON line on stderr. For `lint`, `compile --json`, `simulate` and
`explain` it is byte-identical to the text the matching MCP tool returns, and the MCP result carries
`isError = true` exactly when obp exits non-zero.

`lint`, `compile`, `simulate`, `explain` and `propose` (without `--execute`) call
`ProductToolkit` in openbank-libs-domain, the same functions the agent-service MCP product tools
call, so the CLI and MCP surfaces cannot drift (gate `product-ir-authoring-parity`).

`sign` produces the identity-bound envelope signature with the publish path's own `HmacIrSigner`,
but only under an **offline test key** (`--key-ref test:<name>`, derived from a public constant).
Any other key reference is refused before a key is resolved; obp never reads a real key, and a
test-key signature verifies nowhere a deployment trusts.

`propose` prints the DRAFT revision it would create on the catalog v2 API
(`POST /api/v2/offerings/{id}/revisions`). Only `--execute --catalog-url URL` sends it (https or
loopback; bearer token from `OBP_TOKEN`), and it never publishes — publishing is a human, four-eyes
catalog action (ADR-0259). This is the CLI's only network call.

`diff --behavioural` is the repricing backtest shipped with phase 2. The multi-account
`ProductBehaviourDiff` of phase 4 lives in openbank-libs-testing and `obp diff` is rewired to it
once that lands.

Everything runs offline against the one pure interpreter in `openbank-libs-domain`; output is
canonical JSON. Rates, fees and thresholds in the reference products are illustrative shapes, not
anyone's commercial terms.
