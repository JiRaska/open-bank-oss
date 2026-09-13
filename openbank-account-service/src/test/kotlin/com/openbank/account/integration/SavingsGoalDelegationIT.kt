// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Savings-goal slice of AC7 (issue #2990): a SAVINGS_GOAL grant answers the savings
 * delegation check, never the account READ_ONLY check — and revocation closes it.
 * Savings goals are account metadata (ADR-0153), so the grant keys on the account id.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@QuarkusTestResource(SavingsGoalDelegationIT.InMemoryDelegationChannel::class)
class SavingsGoalDelegationIT {

    class InMemoryDelegationChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("delegation-events-in")

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    private val ownerParty: UUID = UUID.randomUUID()
    private val delegateParty: UUID = UUID.randomUUID()
    private val grantId: UUID = UUID.randomUUID()

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `savings grant answers savings check only and revocation closes it`(): Unit = runBlocking {
        val accountId = openAccount()
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        source.runOnVertxContext(true)

        source.send(delegationEvent("DelegationActivated", accountId))
        assertThat(awaitSavings(accountId, "DEPOSIT", expected = true)).isTrue()
        // A savings grant must NOT leak into the account guard (resource-type separation).
        assertThat(accountReadOnlyAuthorized(accountId)).isFalse()

        source.send(delegationEvent("DelegationRevoked", accountId))
        assertThat(awaitSavings(accountId, "DEPOSIT", expected = false)).isTrue()
    }

    private fun openAccount(): String {
        val response = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {
                  "partyId": "$ownerParty",
                  "productId": "00000000-2222-0000-0000-000000000001",
                  "accountType": "SAVINGS",
                  "currencyCode": "CZK",
                  "legalName": "Savings Delegation IT"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        }
        return response.extract().path("id")
    }

    private suspend fun awaitSavings(accountId: String, intent: String, expected: Boolean): Boolean {
        repeat(40) {
            val authorized: Boolean = (
                Given { this } When {
                    get(
                        "/api/v1/accounts/$accountId/savings-goal/delegation/check" +
                            "?partyId=$delegateParty&intent=$intent",
                    )
                } Then {
                    statusCode(200)
                }
                ).extract().path("authorized")
            if (authorized == expected) return true
            delay(250)
        }
        return false
    }

    private fun accountReadOnlyAuthorized(accountId: String): Boolean = (
        Given { this } When {
            get("/api/v1/accounts/$accountId/authorizations/check?partyId=$delegateParty&role=READ_ONLY")
        } Then {
            statusCode(200)
        }
        ).extract().path("authorized")

    private fun delegationEvent(type: String, accountId: String): String =
        """
        {
          "eventType": "$type",
          "lifecycleRevision": ${if (type == "DelegationActivated") 1 else 2},
          "aggregateId": "$grantId",
          "grantorPartyId": "$ownerParty",
          "granteePartyId": "$delegateParty",
          "resourceType": "SAVINGS_GOAL",
          "resourceId": "$accountId",
          "capabilities": ["SAVINGS_DEPOSIT"],
          "validFrom": "2026-01-01T00:00:00Z",
          "occurredAt": "2026-08-01T12:00:00Z"
        }
        """.trimIndent()
}
