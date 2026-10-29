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
./gradlew :openbank-products:run --args="compile all --out ir"      # DSL -> canonical IR (commit the result)
./gradlew :openbank-products:run --args="compile all --check"       # red when ir/ drifts from the DSL
./gradlew :openbank-products:run --args="test"                      # golden scenarios, shared runner
./gradlew :openbank-products:run --args="diff ir/savings-fixed.json my-reprice.json --behavioural --portfolio portfolios/savings-sample.json"
./gradlew :openbank-products:run --args="explain fee.maintenance"
```

`obp sign` and `obp propose` refuse here by design: signing belongs to product-catalog's
`publish-ir` (four-eyes action `catalog.publish-ir`), proposing is ADR-0352 phase 5.

The reference term deposit refuses any posting that would take it below zero, as savings does.
Capitalisation truncates to the cent and carries the remainder. A scheduled savings fee still posts
in full on a dormant account (accepted in IR v1). See "Replay findings" in
`openbank-libs/docs/product-ir.md`.

Everything runs offline against the one pure interpreter in `openbank-libs-domain`; output is
canonical JSON. Rates, fees and thresholds in the reference products are illustrative shapes, not
anyone's commercial terms.
