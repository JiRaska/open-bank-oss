// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

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
 * The party-resolution sweep is switched off because its first tick lands 2 minutes after boot
 * (see application.yaml) — inside a long suite run — and its account-service calls (absent in a
 * bare `@QuarkusTest`) would deposit error spans this test never asked about. The outbox tick is
 * already off under `%test`, and OTEL is forced on so there are spans to contract at all.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.otel.sdk.disabled" to "false",
        "openbank.aml.party-resolution.enabled" to "false",
    )
}
