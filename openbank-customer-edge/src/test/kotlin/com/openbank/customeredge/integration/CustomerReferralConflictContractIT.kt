// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.openbank.customeredge.contract.StubUpstreamResource
import com.openbank.customeredge.infrastructure.ratelimit.RateLimiter
import io.mockk.every
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.util.UUID

private const val PARTY = "11111111-1111-4111-8111-111111111111"

@QuarkusTest
@QuarkusTestResource(StubUpstreamResource::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$PARTY", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = PARTY)])
class CustomerReferralConflictContractIT {
    @Suppress("UNCHECKED_CAST")
    private val spec: Map<String, Any?> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/openapi.yaml"))
            .use { Yaml().load<Map<String, Any?>>(it) }
    }

    @BeforeEach
    fun reset() {
        StubUpstreamResource.reset()
        val limiter = mockk<RateLimiter>()
        every { limiter.isAllowed(any(), any()) } returns true
        every { limiter.isWithinWindow(any(), any(), any(), any(), any()) } returns true
        QuarkusMock.installMockForType(limiter, RateLimiter::class.java)
    }

    @Test
    fun `invite conflicts use the published body for both upstream reasons`() {
        val programId = UUID.randomUUID()
        val path = "/api/v1/referrals/programs/$programId/invites"
        for ((upstreamReason, publicReason) in listOf(
            "IDEMPOTENCY_KEY_REUSED" to "IDEMPOTENCY_KEY_REUSED",
            "PROGRAM_UNAVAILABLE" to "PROGRAM_UNAVAILABLE",
        )) {
            StubUpstreamResource.stub(path, status = 409, body = """{"reason":"$upstreamReason","error":"refused"}""")
            val response = given()
                .contentType("application/json")
                .header("Idempotency-Key", "invite-$upstreamReason")
                .body("""{"programId":"$programId"}""")
                .post("/customer/v1/referrals/invites")
            assertConflict(response, "/referrals/invites", publicReason)
        }
    }

    @Test
    fun `attribution conflicts use the published body for every public reason`() {
        val token = "A".repeat(24)
        val path = "/api/v1/referrals/invites/$token/attribute"
        for ((upstreamReason, publicReason) in listOf(
            "SELF" to "SELF",
            "ALREADY_ATTRIBUTED" to "ALREADY_ATTRIBUTED",
            "NOT_ATTRIBUTABLE" to "REJECTED",
        )) {
            StubUpstreamResource.stub(path, status = 409, body = """{"reason":"$upstreamReason","error":"refused"}""")
            val response = given()
                .contentType("application/json")
                .header("Idempotency-Key", "attribute-$upstreamReason")
                .body("""{"token":"$token"}""")
                .post("/customer/v1/referrals/attributions")
            assertConflict(response, "/referrals/attributions", publicReason)
        }
    }

    @Test
    fun `the conflict schema rejects a generic error without reason`() {
        assertThat(violations(mapOf("error" to "conflict", "status" to 409)))
            .contains("reason is required")
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertConflict(response: Response, path: String, reason: String) {
        assertThat(response.statusCode).isEqualTo(409)
        assertThat(response.contentType).startsWith("application/json")
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)["post"] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val content = responses.getValue("409")["content"] as Map<String, Map<String, Any?>>
        val schema = content.getValue("application/json")["schema"] as Map<String, String>
        assertThat(schema.getValue("\$ref")).isEqualTo("#/components/schemas/ReferralConflict")
        val body: Map<String, Any?> = response.jsonPath().getMap("")
        assertThat(body["reason"]).isEqualTo(reason)
        assertThat(violations(body)).describedAs("POST $path 409").isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>): List<String> {
        val components = spec["components"] as Map<String, Any?>
        val schemas = components["schemas"] as Map<String, Map<String, Any?>>
        val schema = schemas.getValue("ReferralConflict")
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        val errors = (schema["required"] as List<String>).filterNot(body::containsKey)
            .map { "$it is required" }.toMutableList()
        for ((name, value) in body) {
            if (properties[name]?.get("type") == "string" && value !is String) errors += "$name is not a string"
            val allowed = properties[name]?.get("enum") as? List<String>
            if (allowed != null && value !in allowed) errors += "$name is outside enum"
        }
        return errors
    }
}
