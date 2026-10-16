// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/** Future unannotated standing-order routes must require a bearer on both HTTP listeners. */
@QuarkusTest
@TestProfile(StandingOrderUnannotatedListenerAuthIT.Profile::class)
@QuarkusTestResource(StandingOrderBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class StandingOrderUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/standing-orders/listener-auth-probe"

    class Profile : ProductionListenerProfile()
}

/** Deliberately has no class- or method-level security annotation. */
@Path("/api/v1/standing-orders/listener-auth-probe")
class StandingOrderListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
