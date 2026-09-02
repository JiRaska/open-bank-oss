// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.it.PostgresRedpandaRedisTestResource
import com.openbank.account.it.StubScaChallengeClient
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
 * AC8 E2E (issue #2990): delegate with SAVINGS_PROPOSE_WITHDRAW proposes; the
 * proposal cannot execute; the owner's SCA-bound approval flips it to APPROVED.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@QuarkusTestResource(SavingsProposalIT.InMemoryDelegationChannel::class)
class SavingsProposalIT {

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
    fun `delegate proposes, owner approves with SCA, proposal becomes APPROVED`(): Unit = runBlocking {
        val accountId = openAccount()
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        source.runOnVertxContext(true)
        source.send(delegationEvent("DelegationActivated", accountId))
        awaitSavingsGrant(accountId)

        val proposalId = propose(accountId)
        StubScaChallengeClient.party.set(ownerParty)

        val status: String = (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", ownerParty.toString())
                body("""{"approve": true, "scaSessionId": "${UUID.randomUUID()}"}""")
            } When {
                post("/api/v1/accounts/$accountId/savings-goal/delegation/proposals/$proposalId/decide")
            } Then {
                statusCode(200)
            }
            ).extract().path("status")
        assertThat(status).isEqualTo("APPROVED")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `propose without a grant is 403 and a non-owner decide is 403`(): Unit = runBlocking {
        val accountId = openAccount()

        Given {
            contentType("application/json")
            // Attributed caller on purpose: without the header this would be 403 for the wrong
            // reason and would stop testing the grant check at all.
            header("X-Customer-Party-Id", delegateParty.toString())
            body(
                """{"amountMinor": 1000, "currency": "CZK"}""",
            )
        } When {
            post("/api/v1/accounts/$accountId/savings-goal/delegation/proposals")
        } Then {
            statusCode(403)
        }
    }

    /**
     * The caller's party is no longer something the request can assert (#3164 C3). Previously
     * `decidedByPartyId` was a query parameter, so "only the owner may decide" compared database
     * state against a value the caller chose.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `an unattributed call is refused rather than defaulted`(): Unit = runBlocking {
        val accountId = openAccount()

        val error: String = (
            Given {
                contentType("application/json")
                body("""{"amountMinor": 1000, "currency": "CZK"}""")
            } When {
                post("/api/v1/accounts/$accountId/savings-goal/delegation/proposals")
            } Then {
                statusCode(403)
            }
            ).extract().path("error")

        // The status alone cannot carry this test: a missing grant is ALSO 403, so asserting only
        // the code would pass even if the header were ignored entirely. Found by a falsification
        // probe that made the resource discard the header — this case stayed green while the
        // happy path went red.
        assertThat(error).contains("X-Customer-Party-Id")
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
                  "legalName": "Proposal IT"
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

    private fun propose(accountId: String): String {
        val response = Given {
            contentType("application/json")
            header("X-Customer-Party-Id", delegateParty.toString())
            body(
                """{"amountMinor": 150000, "currency": "CZK", "note": "kolo"}""",
            )
        } When {
            post("/api/v1/accounts/$accountId/savings-goal/delegation/proposals")
        } Then {
            statusCode(202)
        }
        return response.extract().path("proposal.id")
    }

    private suspend fun awaitSavingsGrant(accountId: String) {
        repeat(40) {
            val authorized: Boolean = (
                Given { this } When {
                    get(
                        "/api/v1/accounts/$accountId/savings-goal/delegation/check" +
                            "?partyId=$delegateParty&intent=PROPOSE_WITHDRAW",
                    )
                } Then {
                    statusCode(200)
                }
                ).extract().path("authorized")
            if (authorized) return
            delay(250)
        }
        error("savings grant never became visible")
    }

    private fun delegationEvent(type: String, accountId: String): String =
        """
        {
          "eventType": "$type",
          "lifecycleRevision": 1,
          "aggregateId": "$grantId",
          "grantorPartyId": "$ownerParty",
          "granteePartyId": "$delegateParty",
          "resourceType": "SAVINGS_GOAL",
          "resourceId": "$accountId",
          "capabilities": ["SAVINGS_PROPOSE_WITHDRAW"],
          "validFrom": "2026-01-01T00:00:00Z",
          "occurredAt": "2026-08-01T12:00:00Z"
        }
        """.trimIndent()
}
