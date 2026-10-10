// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.ledger.it.PostgresRedpandaTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(LedgerListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LedgerListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/journals/trial-balance?asOf=2026-01-01"

    class Profile : ProductionListenerProfile()
}
