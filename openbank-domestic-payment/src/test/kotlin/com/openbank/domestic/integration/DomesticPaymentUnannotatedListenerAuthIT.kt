// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/** Future unannotated payment routes must still require a bearer on both HTTP listeners. */
@QuarkusTest
@TestProfile(DomesticPaymentUnannotatedListenerAuthIT.Profile::class)
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_listener_auth_it")],
)
class DomesticPaymentUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/domestic-payments/listener-auth-probe"

    class Profile : ProductionListenerProfile()
}

/** Deliberately has no class- or method-level security annotation. */
@Path("/api/v1/domestic-payments/listener-auth-probe")
class DomesticPaymentListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}

/** The second independently rooted business API needs the same listener-level coverage. */
@QuarkusTest
@TestProfile(DomesticPaymentUnannotatedListenerAuthIT.Profile::class)
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_listener_auth_it")],
)
class BusinessPaymentBatchUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/business-payment-batches/listener-auth-probe"
}

/** Deliberately has no class- or method-level security annotation. */
@Path("/api/v1/business-payment-batches/listener-auth-probe")
class BusinessPaymentBatchListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
