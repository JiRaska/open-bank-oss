// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.integration

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import com.openbank.sdd.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

/** Real HTTP on both listeners, with this service's production listener settings (#12511). */
@QuarkusTest
@TestProfile(SddListenerAuthParityIT.Profile::class)
@QuarkusTestResource(SddBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SddListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/sdd/mandates/recent"

    class Profile : ProductionListenerProfile() {
        // Listener authentication is the subject here. The real OPA sidecar is absent from
        // this isolated test; its availability must not mask bearer-vs-certificate parity.
        override fun extraOverrides() = mapOf(
            "authz.enforce" to "false",
            "quarkus.management.enabled" to "true",
        )
    }
}
