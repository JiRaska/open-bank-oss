// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.it.PostgresTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/** The audit listener verifies a client certificate for transport, never as the API caller identity. */
@QuarkusTest
@TestProfile(AuditListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresTestResource::class)
class AuditListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/audit/integrity"

    class Profile : ProductionListenerProfile() {
        // Exercise listener identity and route roles independently of the external OPA sidecar.
        // AuditAuthzWiringIT separately proves enforced policy decisions over real HTTP.
        override fun extraOverrides() = mapOf("authz.enforce" to "false")
    }
}
