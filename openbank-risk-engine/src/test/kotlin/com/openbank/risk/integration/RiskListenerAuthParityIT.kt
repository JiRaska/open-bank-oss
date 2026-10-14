// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(RiskListenerAuthParityIT.Profile::class)
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/risk/snapshots/00000000-0000-0000-0000-00000000c0de/positions"

    // No such id: 404 is the answer the authenticated route gives (authz runs advisory in tests).
    override val authenticatedStatus = 404

    class Profile : ProductionListenerProfile()
}
