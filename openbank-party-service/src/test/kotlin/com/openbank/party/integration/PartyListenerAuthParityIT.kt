// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

/**
 * #12511: the 8443 listener (`client-auth: required` in %prod) must not turn a trusted client
 * certificate into an authenticated caller. Production listener shape, real HTTP, both ports.
 */
@QuarkusTest
@TestProfile(PartyListenerAuthParityIT.Profile::class)
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_listener_it")],
)
class PartyListenerAuthParityIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/parties/listener-auth-probe"

    @Test
    fun `public metadata remains accessible without bearer`() {
        given().get("/api/v1/info").then().statusCode(200)
        given().get("/api/v1/config").then().statusCode(200)
    }

    class Profile : ProductionListenerProfile()
}

/** A future Party endpoint without a role annotation must still require bearer auth. */
@Path("/api/v1/parties/listener-auth-probe")
class PartyListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
