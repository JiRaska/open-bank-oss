// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/** Real HTTP listener parity for consent's production mTLS and bearer settings (#12511). */
@QuarkusTest
@TestProfile(ConsentListenerAuthParityIT.Profile::class)
@QuarkusTestResource(ConsentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/consents/party/00000000-0000-0000-0000-000000000001"

    class Profile : ProductionListenerProfile() {
        // The OPA sidecar is not part of this authentication test. Do not make its availability
        // decide whether a certificate is treated as an OIDC identity.
        override fun extraOverrides() = mapOf("authz.enforce" to "false")
    }
}
