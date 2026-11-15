// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.InetSocketAddress

@QuarkusTest
@QuarkusTestResource(FinrepAdvisoryAuthorizationTest.UpstreamStubs::class, restrictToAnnotatedClass = true)
class FinrepAdvisoryAuthorizationTest {
    @Inject
    lateinit var registry: MeterRegistry

    @Test
    fun `each reporting route requests its own unscoped inspection`() {
        val actionsByResource = mapOf(
            FinrepResource::class.java to "finrep.template.inspect",
            CorepResource::class.java to "finrep.corep.inspect",
            ReportingPeriodResource::class.java to "finrep.periods.inspect",
            XbrlCsvPreflightResource::class.java to "finrep.preflight.inspect",
        )
        actionsByResource.forEach { (resource, action) ->
            val method = resource.declaredMethods.single {
                it.isAnnotationPresent(GET::class.java) && !Modifier.isStatic(it.modifiers)
            }
            val authorize = method.getAnnotation(Authorize::class.java)
            assertThat(authorize?.action).isEqualTo(action)
            assertThat(authorize?.resource).isEmpty()
        }
    }

    @Test
    @TestSecurity(user = "reporting-operator", roles = [Roles.OPERATOR])
    fun `an advisory denial is recorded and the operator still receives reporting periods`() {
        given().get("/api/v1/finrep/periods").then().statusCode(200)

        val decision = registry.find("openbank.authz.decisions")
            .tags("action", "finrep.periods.inspect", "enforced", "false", "outcome", "deny")
            .counter()
        assertThat(decision?.count()).isEqualTo(1.0)
    }

    @Test
    @TestSecurity(user = "reporting-viewer", roles = [Roles.VIEWER])
    fun `the JWT role gate still rejects viewers`() {
        given().get("/api/v1/finrep/periods").then().statusCode(403)
    }

    class UpstreamStubs : QuarkusTestResourceLifecycleManager {
        private lateinit var server: HttpServer

        override fun start(): Map<String, String> {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/data/openbank/rest/allow") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                exchange.respond("""{"result":false}""")
            }
            server.createContext("/api/v1/ledger/periods") { exchange -> exchange.respond("[]") }
            server.start()
            val url = "http://127.0.0.1:${server.address.port}"
            return mapOf(
                "opa.url" to url,
                "opa.timeout-ms" to "3000",
                "quarkus.oidc-client.client-enabled" to "false",
                "quarkus.rest-client.ledger-service.url" to url,
            )
        }

        override fun stop() {
            if (this::server.isInitialized) server.stop(0)
        }

        private fun HttpExchange.respond(json: String) {
            val body = json.toByteArray()
            responseHeaders.set("Content-Type", "application/json")
            sendResponseHeaders(200, body.size.toLong())
            responseBody.use { it.write(body) }
        }
    }
}
