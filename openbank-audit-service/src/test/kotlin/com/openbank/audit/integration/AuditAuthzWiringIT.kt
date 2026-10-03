// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.it.PostgresTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** Exercise CDI and the real HTTP interceptor; the remote policy decision is a local fixture. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(AuditAuthzWiringIT.EnforcedProfile::class)
class AuditAuthzWiringIT {
    class EnforcedProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf("authz.enforce" to "true")
        override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(PolicyServer::class.java))
    }

    class PolicyServer : QuarkusTestResourceLifecycleManager {
        private lateinit var server: HttpServer

        override fun start(): Map<String, String> {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/data/openbank/rest/allow") { exchange ->
                exchange.use {
                    val input = ObjectMapper().readTree(exchange.requestBody)["input"]
                    val allowed = input["action"].asText() == "audit.verify" &&
                        input["principal"]["roles"].any { it.asText() == "ROLE_AUDITOR" }
                    val response = "{\"result\":{\"allow\":$allowed}}".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.write(response)
                }
            }
            server.start()
            return mapOf("opa.url" to "http://127.0.0.1:${server.address.port}")
        }

        override fun stop() = server.stop(0)
    }

    @Test
    @TestSecurity(user = "audit-reader", roles = ["ROLE_AUDITOR"])
    fun `registered policy bean permits the integrity request when policy allows`() {
        given().get("/api/v1/audit/integrity").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "audit-admin", roles = ["ROLE_ADMIN"])
    fun `registered policy bean enforces a deny even for a role-eligible caller`() {
        given().get("/api/v1/audit/integrity").then().statusCode(403)
    }
}
