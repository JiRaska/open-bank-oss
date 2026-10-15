// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.it

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Isolation is the point, not the settings: the recorder is an application-scoped bean, so
 * without a distinct profile every other test class in this module would deposit its spans in
 * the same recorder and `hasNoErrorSpan()` would be a statement about the whole module's
 * traffic rather than about the accept under test.
 *
 * `authz.enforce` mirrors the other ITs in this module — there is no OPA sidecar in a bare
 * @QuarkusTest and the interceptor fails closed (503) without one. The outbox dispatcher ships
 * enabled in this service, so it is switched off here for the same reason as the fleet's other
 * outbox ITs: its background sends are spans this test never asked about.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Some modules switch the SDK off for tests; without it there are no spans to contract at all.
        "quarkus.otel.sdk.disabled" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
        "quarkus.scheduler.enabled" to "false",
        "authz.enforce" to "false",
    )
}
