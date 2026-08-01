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
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * AC7 (issue #2990): an ACTIVE delegated grant lets the delegate through the guard,
 * a revocation takes it away — end to end through the real projection, never via a
 * synchronous call to delegation-service.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@QuarkusTestResource(DelegationProjectionIT.InMemoryDelegationChannel::class)
class DelegationProjectionIT {

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
    fun `grant activation opens access and revocation closes it`(): Unit = runBlocking {
        val accountId = openAccount()
        // Mirror the real Kafka connector's Vert.x delivery context — without this the
        // suspend @Incoming handler has no duplicated context for the Panache transaction.
        val source: io.smallrye.reactive.messaging.memory.InMemorySource<String> =
            connector.source("delegation-events-in")
        source.runOnVertxContext(true)

        source.send(delegationEvent("DelegationActivated", accountId))
        assertThat(awaitAuthorized(accountId, expected = true)).isTrue()

        source.send(delegationEvent("DelegationRevoked", accountId))
        assertThat(awaitAuthorized(accountId, expected = false)).isTrue()
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
                  "accountType": "CURRENT",
                  "currencyCode": "CZK",
                  "legalName": "Delegation IT"
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

    private suspend fun awaitAuthorized(accountId: String, expected: Boolean): Boolean {
        repeat(40) {
            val authorized: Boolean = (
                Given { this } When {
                    get("/api/v1/accounts/$accountId/authorizations/check?partyId=$delegateParty&role=READ_ONLY")
                } Then {
                    statusCode(200)
                }
                ).extract().path("authorized")
            if (authorized == expected) return true
            delay(250)
        }
        return false
    }

    private fun delegationEvent(type: String, accountId: String): String =
        """
        {
          "eventType": "$type",
          "aggregateId": "$grantId",
          "grantorPartyId": "$ownerParty",
          "granteePartyId": "$delegateParty",
          "resourceType": "ACCOUNT",
          "resourceId": "$accountId",
          "capabilities": ["ACCOUNT_READ_BALANCES"],
          "validFrom": "2026-01-01T00:00:00Z",
          "occurredAt": "2026-08-01T12:00:00Z"
        }
        """.trimIndent()
}
