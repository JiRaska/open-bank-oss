// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.it

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
 * `authz.enforce` goes to false because there is no OPA sidecar under test — with enforcement on,
 * the policy decision point 503s every request (AuditAuthzWiringIT's EnforcedProfile solves the
 * same problem with a stub OPA; for a contract about spans, not authz, switching enforcement off
 * is the narrower change).
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Some modules switch the SDK off for tests; without it there are no spans to contract at all.
        "quarkus.otel.sdk.disabled" to "false",
        "authz.enforce" to "false",
        // A dedicated profile forces this class its own Quarkus boot; port 0 lets Quarkus rebind
        // freely instead of colliding with the fixed default test port other profiles expect.
        "quarkus.http.test-port" to "0",
    )
}
