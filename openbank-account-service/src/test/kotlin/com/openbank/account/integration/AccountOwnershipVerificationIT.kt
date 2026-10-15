// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * ADR-0335 D2 through real HTTP: the route is served, the answer is exactly {owned, active}, and
 * an unknown IBAN and another party's IBAN are indistinguishable.
 */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_ownership_it")],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AccountOwnershipVerificationIT {

    companion object {
        private val owner = UUID.randomUUID()
        private val productId = UUID.fromString("00000000-2222-0000-0000-000000000001")
        private var iban: String? = null
        private var accountId: String? = null
        private const val PATH = "/api/v1/accounts/ownership-verifications"
        private const val UNKNOWN_IBAN = "CZ6508000000192000145399"
    }

    @Test
    @Order(1)
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an account exists for the owner`() {
        iban = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """{"partyId":"$owner","productId":"$productId","accountType":"CURRENT","currencyCode":"CZK","legalName":"Owner"}""",
            )
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        } Extract {
            path("accountNumber")
        }
        assertThat(iban).isNotBlank()
        accountId =
            Given { this } When { get("/api/v1/accounts/iban/$iban") } Then { statusCode(200) } Extract { path("id") }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
    fun `the owner's active account verifies as owned and active, and nothing else is returned`() {
        val body = verify(iban!!, owner, expect = 200)
        assertThat(body.keys).containsExactlyInAnyOrder("owned", "active", "accountId")
        assertThat(body["owned"]).isEqualTo(true)
        assertThat(body["active"]).isEqualTo(true)
        assertThat(body["accountId"]).isEqualTo(accountId)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
    fun `another party's account and an unknown IBAN give the same answer`() {
        val notMine = verify(iban!!, UUID.randomUUID(), expect = 200)
        val unknown = verify(UNKNOWN_IBAN, owner, expect = 200)
        assertThat(notMine).isEqualTo(mapOf("owned" to false, "active" to false))
        assertThat(unknown).isEqualTo(notMine)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
    fun `a request without iban or partyId is a 400, not a 500`() {
        listOf("""{"partyId":"$owner"}""", """{"iban":"$UNKNOWN_IBAN"}""", "{}").forEach { payload ->
            Given {
                contentType("application/json")
                body(payload)
            } When {
                post(PATH)
            } Then {
                statusCode(400)
            }
        }
    }

    @Test
    @Order(5)
    fun `an anonymous caller is refused`() {
        Given {
            contentType("application/json")
            body("""{"iban":"$UNKNOWN_IBAN","partyId":"$owner"}""")
        } When {
            post(PATH)
        } Then {
            statusCode(401)
        }
    }

    private fun verify(iban: String, partyId: UUID, expect: Int): Map<String, Any?> = Given {
        contentType("application/json")
        body("""{"iban":"$iban","partyId":"$partyId"}""")
    } When {
        post(PATH)
    } Then {
        statusCode(expect)
    } Extract {
        jsonPath().getMap("")
    }
}
