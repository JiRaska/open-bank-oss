// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.it

import com.openbank.swift.integration.SwiftOutboxAtomicityIT
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Gives the trace-contract IT its own Quarkus application instance, and therefore its own
 * [com.openbank.libs.testing.trace.RecordingSpanExporter].
 *
 * Beyond the recorder isolation shared with the other trace contracts (see
 * [com.openbank.swift.it.TraceContractRecorder]), this profile carries the two settings the
 * swift flow needs to reach its write path at all, mirroring
 * [SwiftOutboxAtomicityIT.SchemeSubmissionNoDispatchProfile]:
 *
 *  - `openbank.swift.scheme-submission.enabled=true` — ADR-0104 D4's pilot flag defaults OFF;
 *    with it off `submitToScheme` returns before the scheme verdict write and the message would
 *    sit in VALIDATED with only the create write behind it,
 *  - the enabled alternatives [SwiftOutboxAtomicityIT.AcceptingSchemeGateway] and
 *    [SwiftOutboxAtomicityIT.AccountIdDrivenSettlement] stand in for the clearing simulator and
 *    the settlement leg, so no outbound HTTP (which would surface as error spans this contract
 *    never asked about) is attempted.
 *
 * Only literals here: a profile loads in a different classloader from the test class, so a
 * computed value would not be the value the application sees.
 */
class TraceContractProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // Some modules switch the SDK off for tests; without it there are no spans to contract at all.
        "quarkus.otel.sdk.disabled" to "false",
        "openbank.outbox.dispatch-enabled" to "false",
        "openbank.swift.scheme-submission.enabled" to "true",
        // %test fixes this build-time property to false; without pinning it here the profiled
        // boot reads the extension default (true) and ConfigRecorder refuses to start the app
        // ("build time property cannot be changed at runtime"). Measured on sepa-payment, which
        // carries the identical %test shape, 2026-10-14.
        "quarkus.oidc-client.enabled" to "false",
    )

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(
        SwiftOutboxAtomicityIT.AcceptingSchemeGateway::class.java,
        SwiftOutboxAtomicityIT.AccountIdDrivenSettlement::class.java,
    )
}
