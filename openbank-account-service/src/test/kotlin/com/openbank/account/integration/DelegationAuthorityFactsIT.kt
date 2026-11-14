// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
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
 * `GET /api/v1/accounts/{accountId}/delegation/authority-facts` over real HTTP (ADR-0375 D1).
 *
 * ### Why this cannot be a unit test
 *
 * `AuthorizationServiceTest` can cover the filtering, and cannot see the two things that have
 * actually broken endpoints in this repo. A test that calls a resource class cannot tell a SERVED
 * route from an unserved one — a top-level function between `@Path` and its class once stole the
 * annotation and every `POST /mcp` answered 404 on a running pod while the class-level test stayed
 * green. And nothing below HTTP observes what JAX-RS actually serialises, which is where the
 * no-oracle property lives. This also happens to be the only way to reach the reactive Panache
 * projection at all: a bare `@QuarkusTest` thread throws `No current Vertx context found`.
 *
 * ### What each test is really discriminating
 *
 * The unknown-account case is the security property: it must be INDISTINGUISHABLE from an account
 * that exists and has no grant for the party. Asserting `200` alone would pass against an endpoint
 * that leaks existence in the body, so the body is compared too.
 *
 * The grant case is the positive control, and it is not optional: without it, every assertion here
 * passes just as happily against an endpoint that returns an empty list for everything — which
 * would be a silent outage of the authority step rather than a leak.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
@QuarkusTestResource(DelegationAuthorityFactsIT.InMemoryDelegationChannel::class)
class DelegationAuthorityFactsIT {

    class InMemoryDelegationChannel : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory(
            "delegation-events-in",
            "approval-group-revisions-in",
        )

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    private val ownerParty: UUID = UUID.randomUUID()
    private val delegateParty: UUID = UUID.randomUUID()
    private val strangerParty: UUID = UUID.randomUUID()
    private val grantId: UUID = UUID.randomUUID()

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `the route is served and an unknown account answers an empty list, not 404`() {
        val unknown = UUID.randomUUID()

        val json = facts(unknown, delegateParty)

        // 404 would be the natural implementation and is the defect: it distinguishes an account
        // that exists from one that does not, for any party the caller can name.
        assertThat(json.getList<kotlin.Any>("grants")).isEmpty()
        // No owner for an unknown account: revealing one would be the existence oracle this
        // endpoint is written to avoid, and `grants` being empty is then indistinguishable from an
        // account that exists and has no grant for the party.
        assertThat(json.getString("ownerPartyId")).isNull()
        assertThat(json.getString("accountId")).isEqualTo(unknown.toString())
        assertThat(json.getString("partyId")).isEqualTo(delegateParty.toString())
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `an existing account with no grant is indistinguishable from an unknown one`() {
        val accountId = openAccount()

        val real = facts(accountId, strangerParty).getMap<String, kotlin.Any?>("$")
        val unknown = facts(UUID.randomUUID(), strangerParty).getMap<String, kotlin.Any?>("$")

        // Compared with the account and party keys removed, because those legitimately differ.
        // What must NOT differ is anything else about the shape of the answer.
        assertThat(real.keys).isEqualTo(unknown.keys)
        assertThat(real["grants"]).isEqualTo(unknown["grants"])
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `an active grant is returned with the identity the chain needs to name it`(): Unit = runBlocking {
        val accountId = openAccount()
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        // Mirrors the real Kafka connector's delivery context; without it the suspend @Incoming
        // handler has no duplicated Vert.x context for the Panache transaction.
        source.runOnVertxContext(true)
        source.send(delegationActivated(accountId))

        val json = awaitGrant(accountId)

        assertThat(json.getString("grants[0].grantId")).isEqualTo(grantId.toString())
        assertThat(json.getString("grants[0].grantorPartyId")).isEqualTo(ownerParty.toString())
        assertThat(json.getList<String>("grants[0].capabilities")).containsExactly("ACCOUNT_INITIATE_PAYMENT")
        assertThat(json.getString("grants[0].validFrom")).isNotBlank()
        // Read in the same snapshot as the grants, so the rail decides owner-vs-delegate once.
        assertThat(json.getString("ownerPartyId")).isEqualTo(ownerParty.toString())
        // `active` is deliberately absent from the wire: every grant returned is active, and a
        // field that is always true is an invitation to ship a path where it is not.
        assertThat(json.getMap<String, kotlin.Any?>("grants[0]")).doesNotContainKey("active")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `a grant held by one party is not visible to another`(): Unit = runBlocking {
        val accountId = openAccount()
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        source.runOnVertxContext(true)
        source.send(delegationActivated(accountId))

        awaitGrant(accountId)

        assertThat(facts(accountId, strangerParty).getList<kotlin.Any>("grants")).isEmpty()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `a missing partyId is a 400, not a 500`() {
        // #3104 — a suspend handler emits no Kotlin null intrinsic at the JAX-RS boundary, so the
        // absent parameter reaches the body as null; without the explicit guard this is a 500,
        // which is the exact case the guard was written for.
        Given { this } When {
            get("/api/v1/accounts/${UUID.randomUUID()}/delegation/authority-facts")
        } Then {
            statusCode(400)
        }
    }

    private fun openAccount(): UUID {
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {"partyId":"$ownerParty","productId":"${UUID.randomUUID()}","accountType":"CURRENT",
                 "currencyCode":"CZK","legalName":"Authority Facts IT"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/accounts")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        return UUID.fromString(id)
    }

    private fun facts(accountId: UUID, partyId: UUID) = Given { this } When {
        get("/api/v1/accounts/$accountId/delegation/authority-facts?partyId=$partyId")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath()
    }

    /** The projection is event-fed, so the grant arrives asynchronously. */
    private suspend fun awaitGrant(accountId: UUID): io.restassured.path.json.JsonPath {
        repeat(ATTEMPTS) {
            val json = facts(accountId, delegateParty)
            if (json.getList<kotlin.Any>("grants").isNotEmpty()) return json
            delay(POLL_MILLIS)
        }
        throw AssertionError("the grant never appeared — the projection did not converge")
    }

    private fun delegationActivated(accountId: UUID): String =
        """
        {
          "eventType": "DelegationActivated",
          "lifecycleRevision": 1,
          "aggregateId": "$grantId",
          "grantorPartyId": "$ownerParty",
          "granteePartyId": "$delegateParty",
          "resourceType": "ACCOUNT",
          "resourceId": "$accountId",
          "capabilities": ["ACCOUNT_INITIATE_PAYMENT"],
          "validFrom": "2026-01-01T00:00:00Z",
          "occurredAt": "2026-08-01T12:00:00Z"
        }
        """.trimIndent()

    private companion object {
        const val ATTEMPTS = 40
        const val POLL_MILLIS = 250L
    }
}
