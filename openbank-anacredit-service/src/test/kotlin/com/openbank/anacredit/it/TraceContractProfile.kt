// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.it

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Isolation is the point, not the settings: the recorder is an application-scoped bean, so
 * without a distinct profile every other test class in this module would deposit its spans in
 * the same recorder and `hasNoErrorSpan()` would be a statement about the whole module's
 * traffic rather than about the exposure registration under test.
 *
 * `authz.enforce` mirrors the other ITs in this module — the resource is annotated with
 * `@Authorize` and there is no OPA sidecar in a bare @QuarkusTest, so enforcement is advisory
 * here.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : com.openbank.libs.testing.trace.TraceContractProfile() {
    override val disableOutboxDispatcher: Boolean = false

    override fun extraConfigOverrides(): Map<String, String> = mapOf(
        "authz.enforce" to "false",
    )
}
