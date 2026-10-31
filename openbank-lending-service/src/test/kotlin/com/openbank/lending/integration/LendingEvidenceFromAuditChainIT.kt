// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * `GET /applications/{id}/evidence` now reads audit-service's chain (ADR-0214 D3, #11900), through
 * the REAL rest client and header factory against a stub audit-service. The stub echoes the
 * `Authorization` header it received into the entry's payload, which is how the test proves — with
 * no state shared across classloaders — that the CALLER's token went out and nothing else did.
 */
@QuarkusTest
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
@QuarkusTestResource(LendingEvidenceFromAuditChainIT.StubAuditService::class)
class LendingEvidenceFromAuditChainIT {

    /** Behaviour is chosen by the requested aggregate id, so the test needs no handle on the stub. */
    class StubAuditService : QuarkusTestResourceLifecycleManager {
        private lateinit var server: HttpServer

        override fun start(): Map<String, String> {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/v1/audit/evidence/") { exchange ->
                exchange.use {
                    val id = exchange.requestURI.path.substringAfterLast('/')
                    val auth = exchange.requestHeaders.getFirst("Authorization") ?: "none"
                    val (status, body) = when (id) {
                        REFUSED_ID -> 403 to "{}"
                        BROKEN_ID -> 500 to "{}"
                        else -> 200 to """{"aggregateId":"$id","attestation":"audit-chain","entryCount":1,
                            "truncated":false,"tampered":true,"hashStatusCounts":{"MISMATCH":1},
                            "fullChainVerification":"/api/v1/audit/integrity","entries":[{"entryId":"e-1",
                            "eventType":"lending.application.submitted","aggregateType":"LOAN_APPLICATION",
                            "sourceService":"lending-service","occurredAt":"2026-09-01T10:00:00Z",
                            "occurredAtSource":"EVENT","recordedAt":"2026-09-01T10:00:01Z",
                            "payload":"${auth.replace("\"", "")}","hashStatus":"MISMATCH"}]}"""
                    }
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
            server.start()
            return mapOf("quarkus.rest-client.audit-service.url" to "http://127.0.0.1:${server.address.port}")
        }

        override fun stop() = server.stop(0)
    }

    @Test
    @TestSecurity(user = "risk-officer", roles = ["ROLE_CREDIT_RISK"])
    fun `the bundle comes from the audit chain, read with the caller's own token`() {
        given().header("Authorization", "Bearer caller-token-of-risk-officer")
            .get("/api/v1/lending/applications/$CHAIN_ID/evidence").then()
            .statusCode(200)
            .body("attestation", equalTo("audit-chain"))
            .body("tampered", equalTo(true))
            .body("truncated", equalTo(false))
            .body("eventCount", equalTo(1))
            .body("events[0].hashStatus", equalTo("MISMATCH"))
            .body("events[0].sourceService", equalTo("lending-service"))
            // The stub echoed what it received: the caller's bearer, verbatim — no service token.
            .body("events[0].payload", equalTo("Bearer caller-token-of-risk-officer"))
            .body("requestedBy", equalTo("risk-officer"))
    }

    @Test
    @TestSecurity(user = "risk-officer", roles = ["ROLE_CREDIT_RISK"])
    fun `audit-service refusing the caller is passed through as 403, not dressed up as an empty trail`() {
        given().header("Authorization", "Bearer t").get("/api/v1/lending/applications/$REFUSED_ID/evidence")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "risk-officer", roles = ["ROLE_CREDIT_RISK"])
    fun `an audit-service failure is 503, never a fallback to the outbox`() {
        given().header("Authorization", "Bearer t").get("/api/v1/lending/applications/$BROKEN_ID/evidence")
            .then().statusCode(503)
    }

    companion object {
        const val CHAIN_ID = "a0a0a0a0-a0a0-4a0a-8a0a-a0a0a0a0a0a0"
        const val REFUSED_ID = "b0b0b0b0-b0b0-4b0b-8b0b-b0b0b0b0b0b0"
        const val BROKEN_ID = "c0c0c0c0-c0c0-4c0c-8c0c-c0c0c0c0c0c0"
    }
}
