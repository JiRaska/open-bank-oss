// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.it

import com.openbank.cardprocessing.integration.CardAuthorizationOutboxIT
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Mirrors [CardAuthorizationOutboxIT.StubbedIssuerProfile]: the issuer stays a CDI alternative
 * (no network — what is under contract is this service's own path) and the outbox dispatcher is
 * off so no background tick races the assertions. Isolation on top of that is the point: the
 * recorder is an application-scoped bean, so a shared profile would let the sibling IT's traffic
 * into the same recorder and `hasNoErrorSpan()` would be a statement about the whole module's
 * spans rather than about the one authorisation under test.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Some modules switch the SDK off for tests; without it there are no spans to contract at all.
        "quarkus.otel.sdk.disabled" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
    )

    override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(
        CardAuthorizationOutboxIT.StubIssuer::class.java,
    )
}
