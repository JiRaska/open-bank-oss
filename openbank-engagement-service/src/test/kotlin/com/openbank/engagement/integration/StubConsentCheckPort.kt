// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import com.openbank.engagement.application.port.out.ConsentCheckPort
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-only consent adapter, same shape as `StubScaChallengeClient` (account-service): consent-
 * service does not run in this IT stack, and stubbing at the port — rather than via WireMock +
 * the OIDC client-credentials filter — proves the thing this IT actually exists to prove (the
 * REST endpoint → use case → Panache reactive persistence path, over real HTTP, on the Vert.x
 * context only a real request carries), without also depending on an OIDC token server being
 * reachable in the test JVM.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class StubConsentCheckPort : ConsentCheckPort {
    override suspend fun hasActiveConsent(partyId: java.util.UUID, scope: String): Boolean = granted.get()

    companion object {
        val granted: AtomicBoolean = AtomicBoolean(true)
    }
}
