// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.risk.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/** #12511: a future unannotated API route must require a bearer on both listeners. */
@QuarkusTest
@TestProfile(RiskListenerAuthParityIT.Profile::class)
@QuarkusTestResource(RiskSnapshotApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RiskUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/risk-listener-auth-probe"
}

/** Test-only route without class or method security annotations. */
@Path("/api/v1/risk-listener-auth-probe")
class RiskListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
