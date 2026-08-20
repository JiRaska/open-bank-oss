// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.integration

import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * HTTP-level proof for the ADR-0031 D5 anchor endpoints (issue #5838).
 *
 * This exists because the module's other anchor endpoint coverage is reflection-based, and
 * reflection over a resource class cannot tell a SERVED route from an unserved one — this repo has
 * already shipped an endpoint whose `@Path` bound to a stray top-level function, so every POST
 * answered 404 on a running pod while the class-level unit test stayed green. Only a `@QuarkusTest`
 * driving real HTTP through the JAX-RS runtime can see that, so every assertion below goes over
 * the wire.
 *
 * It is also the deployment-facing fail-closed proof. The test environment has no OpenBao and no
 * projected ServiceAccount token — i.e. exactly the "key unavailable" condition — so a capture must
 * be REFUSED over HTTP and must leave the append-only `audit_anchor` table empty. If the tolerant
 * pre-#5838 behaviour ever returns, `capture is refused` goes red on the status code and
 * `no unsigned anchor is stored` goes red on the row count.
 */
@QuarkusTest
@TestProfile(AuditAnchorEndpointIT.NoPdpProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
class AuditAnchorEndpointIT {

    /**
     * Turns off the OPA authorization interceptor only. It fails closed with 503 when no PDP
     * sidecar is reachable, which is correct behaviour and would mask every status code this test
     * is about. Role gating itself stays on — the 401s below are real. Literal values on purpose:
     * a profile loads in a different classloader from the test class, so anything computed in a
     * companion object is initialised twice and the two halves disagree.
     */
    class NoPdpProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Inject
    lateinit var repository: AuditRepository

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun givenSomethingToAnchor() {
        onEventLoop {
            repository.save(
                AuditEntry(
                    id = UUID.randomUUID(),
                    eventType = "anchor.endpoint.it",
                    aggregateType = "ACCOUNT",
                    aggregateId = UUID.randomUUID().toString(),
                    actorId = "tester",
                    actorType = "HUMAN",
                    payload = """{"k":"v"}""",
                    sourceService = "openbank-audit-service",
                    correlationId = "corr-anchor-it",
                    occurredAt = Instant.parse("2026-08-20T09:00:00Z"),
                    recordedAt = Instant.parse("2026-08-20T09:00:01Z"),
                    occurredAtSource = OccurredAtSource.EVENT,
                ),
            )
        }
    }

    @Test
    fun `anchor routes are actually served and are not anonymous`() {
        for (path in listOf(
            "/api/v1/audit/anchors",
            "/api/v1/audit/anchors/verify",
            "/api/v1/audit/anchors/public-key",
        )) {
            val status = given().`when`().get(path).then().extract().statusCode()
            assertThat(status)
                .describedAs("%s must be a registered, authenticated route — 404 means it is not served at all", path)
                .isNotEqualTo(NOT_FOUND)
                .isEqualTo(UNAUTHORIZED)
        }
        val captureStatus = given().`when`().post("/api/v1/audit/anchors/capture").then().extract().statusCode()
        assertThat(captureStatus)
            .describedAs("POST /anchors/capture must be a registered route (405/404 means it is not)")
            .isEqualTo(UNAUTHORIZED)
    }

    @Test
    @TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
    fun `verify reports UNVERIFIABLE or INTACT over HTTP, never a bare success field`() {
        val body = given().`when`().get("/api/v1/audit/anchors/verify").then().statusCode(OK).extract().jsonPath()
        assertThat(body.getString("status")).isIn("INTACT", "UNVERIFIABLE", "BROKEN")
        assertThat(body.getObject("unverifiableCount", Int::class.java)).isNotNull()
    }

    @Test
    @TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
    fun `public key material is reported unavailable rather than served empty`() {
        // No OpenBao in the test environment: the endpoint must say so with 503, not 200 + null.
        given().`when`().get("/api/v1/audit/anchors/public-key")
            .then().statusCode(SERVICE_UNAVAILABLE)
    }

    @Test
    @TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
    fun `capture is refused when the key is unavailable and no unsigned anchor is stored`() {
        givenSomethingToAnchor()

        given().`when`().post("/api/v1/audit/anchors/capture")
            .then().statusCode(SERVICE_UNAVAILABLE)
            .body("status", org.hamcrest.Matchers.equalTo("SIGNER_UNAVAILABLE"))

        val anchors = given().`when`().get("/api/v1/audit/anchors")
            .then().statusCode(OK).extract().jsonPath().getList<Any>("$")
        assertThat(anchors)
            .describedAs("a capture that could not be signed must leave NO row behind")
            .isEmpty()
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val SERVICE_UNAVAILABLE = 503
    }
}
