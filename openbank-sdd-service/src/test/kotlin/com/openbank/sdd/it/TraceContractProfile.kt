// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.it

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Isolation is the point, not the settings: the recorder is an application-scoped bean, so without
 * a distinct profile every other test class in this module would deposit its spans in the same
 * recorder and `hasNoErrorSpan()` would be a statement about the whole module's traffic rather
 * than about the one operation under test.
 *
 * The other two overrides are not incidental:
 *  - `openbank.outbox.dispatch-enabled=false` — sdd ships it true in BOTH its default and `%test`
 *    profiles; the background dispatcher UPDATEs claimed rows and would deposit spans this test
 *    never asked about.
 *  - `authz.enforce=false` — sdd defaults AUTHZ_ENFORCE to true and no OPA sidecar is reachable
 *    in the test, so every `@Authorize`-annotated write would fail closed with 503 (measured on
 *    SddOutboxAtomicityIT). Advisory mode still computes and records the decision; the contract
 *    is about the trace shape of the write itself.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.otel.sdk.disabled" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
        "authz.enforce" to "false",
    )
}
