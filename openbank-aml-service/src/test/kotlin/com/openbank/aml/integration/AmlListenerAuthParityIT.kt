// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.integration

import com.openbank.aml.it.PostgresRedisTestResource
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
@TestProfile(AmlListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/aml/cases?limit=1"

    class Profile : ProductionListenerProfile()
}
