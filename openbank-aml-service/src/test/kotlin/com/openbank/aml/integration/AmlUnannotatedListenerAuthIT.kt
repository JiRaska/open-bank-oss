// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.integration

import com.openbank.aml.it.PostgresRedisTestResource
import com.openbank.libs.testing.mtls.ListenerAuthParityConformance
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Test

/** New business routes must require a bearer even when their author omits a role annotation. */
@QuarkusTest
@TestProfile(AmlListenerAuthParityIT.Profile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class AmlUnannotatedListenerAuthIT : ListenerAuthParityConformance() {
    override val path = "/api/v1/aml/cases/listener-auth-probe"

    @Test
    fun `public metadata and management health remain accessible without bearer`() {
        given().get("/api/v1/info").then().statusCode(200)
        given().get("/api/v1/config").then().statusCode(200)
        given().get("/q/health/ready").then().statusCode(200)
    }
}

/** Test-only resource with no class or method security annotation. */
@Path("/api/v1/aml/cases/listener-auth-probe")
class AmlListenerAuthProbe {
    @GET
    fun probe(): Response = Response.ok().build()
}
