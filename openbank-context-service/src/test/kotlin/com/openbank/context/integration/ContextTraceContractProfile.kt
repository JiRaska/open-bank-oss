// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.integration

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Isolation is the point, not the settings: the recorder is an application-scoped bean, so without
 * a distinct profile every other test class in this module would deposit its spans in the same
 * recorder and `hasNoErrorSpan()` would be a statement about the whole module's traffic rather than
 * about the one operation under test.
 *
 * The scheduler is switched off: this service ships two commitment relays (`@Scheduled`, every
 * 5 s) whose background outbox polls are spans this test never asked about. There is no fleet
 * outbox dispatcher here — the commitment relays above are the service's own — so the base
 * profile's `openbank.outbox.dispatch-enabled` pin is skipped rather than asserted about a
 * config key nobody reads.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class ContextTraceContractProfile : com.openbank.libs.testing.trace.TraceContractProfile() {
    override val disableOutboxDispatcher: Boolean = false

    override fun extraConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.scheduler.enabled" to "false",
    )
}
