// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.integration

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import java.util.UUID

/** Exercises actual JAX-RS header binding with the gated writer enabled. */
@QuarkusTest
@TestProfile(LendingGraphWriterHttpIT.WriterEnabled::class)
@QuarkusTestResource(LendingBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(value = PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class LendingGraphWriterHttpIT {
    class WriterEnabled : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.lending.graph.writer-enabled" to "true")
    }

    @Test
    @TestSecurity(user = "maker", roles = ["ROLE_LENDING_OFFICER"])
    fun `proposal rejects missing and blank key on served endpoint`() {
        val route = "/api/v1/lending/graph/loans/${UUID.randomUUID()}/guarantees"
        val body = """{"contractId":"${UUID.randomUUID()}","revision":1,"supersedesGuaranteeId":null,
            "guarantorPartyId":"${UUID.randomUUID()}",
            "capAmount":500.00,"currency":"EUR","coverageFraction":0.5,"seniority":1,
            "validFrom":"2026-09-25T10:00:00Z","validTo":null,"sourceDocumentId":"${UUID.randomUUID()}",
            "sourceSha256":"${"a".repeat(64)}"}
        """.trimIndent()
        Given {
            contentType("application/json")
            body(body)
        } When {
            post(route)
        } Then {
            statusCode(400)
            body("message", containsString("Idempotency-Key"))
        }
        Given {
            contentType("application/json")
            header("Idempotency-Key", " ")
            body(body)
        } When {
            post(route)
        } Then {
            statusCode(400)
            body("message", containsString("Idempotency-Key"))
        }
    }

    @Test
    @TestSecurity(user = "checker", roles = ["ROLE_CREDIT_RISK"])
    fun `decision rejects missing and blank key on served endpoint`() {
        val route = "/api/v1/lending/graph/loans/${UUID.randomUUID()}/guarantees/${UUID.randomUUID()}/decision"
        Given {
            contentType("application/json")
            body("""{"decision":"APPROVED"}""")
        } When {
            post(route)
        } Then {
            statusCode(400)
            body("message", containsString("Idempotency-Key"))
        }
        Given {
            contentType("application/json")
            header("Idempotency-Key", " ")
            body("""{"decision":"APPROVED"}""")
        } When {
            post(route)
        } Then {
            statusCode(400)
            body("message", containsString("Idempotency-Key"))
        }
    }
}
