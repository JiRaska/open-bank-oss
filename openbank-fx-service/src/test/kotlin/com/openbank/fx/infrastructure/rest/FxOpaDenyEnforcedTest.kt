// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.rest

import com.openbank.fx.integration.FxBootSmokeIT
import com.openbank.fx.it.PostgresRedisTestResource
import com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint
import com.openbank.libs.authz.PolicyDecisionPoint
import com.sun.net.httpserver.HttpServer
import io.quarkus.arc.ClientProxy
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Negative case for the libs-runtime OPA producer (`OpaPolicyDecisionPointProducer`): with
 * `authz.enforce=true` and a stub sidecar on `opa.url`, an OPA deny must answer 403. The stub
 * allows exactly one principal id, so the same test carries its own must-ALLOW control — a 403 for
 * everyone would otherwise pass the deny assertion vacuously.
 */
@QuarkusTest
@TestProfile(FxOpaDenyEnforcedTest.EnforcedWithStubOpa::class)
@QuarkusTestResource(FxBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class FxOpaDenyEnforcedTest {

    @Inject
    lateinit var pdp: PolicyDecisionPoint

    @Test
    fun `the bound PDP is the real OPA sidecar client from the lib producer`() {
        assertThat(ClientProxy.unwrap(pdp)).isInstanceOf(OpaSidecarPolicyDecisionPoint::class.java)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `an OPA deny is enforced as 403`() {
        Given { this } When { get("/api/v1/fx/rates") } Then { statusCode(403) }
    }

    @Test
    @TestSecurity(user = ALLOWED_USER, roles = ["ROLE_VIEWER"])
    fun `control - an OPA allow reaches the handler`() {
        Given { this } When { get("/api/v1/fx/rates") } Then { statusCode(200) }
    }

    class EnforcedWithStubOpa : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "true")

        override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
            listOf(QuarkusTestProfile.TestResourceEntry(StubOpa::class.java))
    }

    class StubOpa : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null

        override fun start(): Map<String, String> {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val allow = body.contains(ALLOWED_USER)
                val out = """{"result":{"allow":$allow,"reason":"stub"}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, out.size.toLong())
                ex.responseBody.use { it.write(out) }
            }
            s.start()
            server = s
            return mapOf("opa.url" to "http://127.0.0.1:${s.address.port}")
        }

        override fun stop() {
            server?.stop(0)
        }
    }

    private companion object {
        const val ALLOWED_USER = "00000000-0000-0000-0000-000000000098"
    }
}
