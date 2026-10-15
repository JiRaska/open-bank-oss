// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.it

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Isolation is the point, not the settings: the recorder is an application-scoped bean, so
 * without a distinct profile every other test class in this module would deposit its spans in
 * the same recorder and `hasNoErrorSpan()` would be a statement about the whole module's
 * traffic rather than about the one posting under test. The outbox dispatcher is switched off
 * for the same reason — transaction-service enables it in application.yaml and the test
 * properties do not override it, so its background sends would be spans this test never asked
 * about.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Transaction's test properties switch the SDK off; without it there are no spans.
        "quarkus.otel.sdk.disabled" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
    )
}
