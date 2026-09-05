// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.openbank.account.it.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
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
import java.time.LocalDate
import java.util.UUID

/**
 * **A `LEGACY_AUTHORIZATION` answer carries NO `grantorPartyId`, and that omission is
 * LOAD-BEARING on the money path** (issue #2993, ADR-0232 D1/D3/D5).
 *
 * ### What this pins, and why it is not a formatting test
 *
 * `authorizeDelegatedPayment` answers `authorized: true` for a row in the un-reconciled legacy
 * `account_authorizations` table — the store ADR-0232 D1 exists to retire, which nothing
 * back-fills, nothing writes through to, and nothing reconciles. That verdict reaches
 * `CustomerEdgeResource.resolveDebitAuthority`, the domestic-payment route's debit guard, which
 * refuses on `decision?.authorized != true || grantor == null`.
 *
 * So the ONLY thing standing between the legacy store and a live debit is the single line in
 * [com.openbank.account.infrastructure.rest.DelegatedPaymentAuthorizationResource] that emits
 * `delegationId`/`grantorPartyId` **exclusively** for the `DELEGATED` outcome. A legacy-only
 * delegate cannot initiate a payment today only because that field is absent.
 *
 * **Nothing intended that.** The refusal falls out of the edge needing a grantor id to re-fetch
 * the account as its owner — not out of anyone deciding the legacy store must not carry money.
 * The property was entirely untested until this test and its customer-edge sibling
 * (`LegacyDelegationArmRefusedIT`) existed.
 *
 * ### The direction a future change must NOT take
 *
 * The two arms of that response visibly disagree: one names its grant, the other does not. The
 * obvious tidy-up — *"the response should always carry `grantorPartyId`; it is the account owner
 * in both cases"* — is correct-looking, one line, and **silently puts an un-reconciled store on
 * the money path**. Before this test, no test in the fleet would have gone red.
 *
 * Widening is the wrong direction. Until the ADR-0232 D1 dual-run exists (write-through, backfill,
 * reconciliation — measured at zero on #2993 across five passes), the two stores can only be made
 * to agree by trusting the **wider and unreconciled** one. The drift is the safer state: revoking
 * a delegation grant does not close the legacy row, so a response that named a grantor for it
 * would let money move under an authority the system of record has already withdrawn.
 *
 * If the arms must converge, converge them by *narrowing* — stop answering `authorized: true` for
 * the legacy arm — or by building the dual-run first. Never by adding the field.
 *
 * ### Why this is an IT and not a unit test
 *
 * `AuthorizeDelegatedPaymentTest` covers the use case's verdict, and cannot see this: the
 * omission happens in the resource's response assembly, one layer above it, and a test that calls
 * the resource class cannot tell a served route from an unserved one nor observe what JAX-RS
 * actually serialises. This drives the real route over real HTTP against a real row. That is also
 * the only way to reach the reactive Panache repositories at all — a bare `@QuarkusTest` thread
 * throws `No current Vertx context found`.
 *
 * Falsified, not assumed: adding `grantorPartyId` to the legacy branch of the resource turns
 * `theLegacyArmOmitsTheGrantor` red here and flips the customer-edge sibling's 403 to a 201.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaRedisTestResource::class)
@QuarkusTestResource(LegacyArmOmitsGrantorIT.InMemoryDelegationChannel::class)
class LegacyArmOmitsGrantorIT {

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
    private val operator = "00000000-0000-0000-0000-0000000000aa"

    // ── the invariant ──────────────────────────────────────────────────────────────────────

    /**
     * The legacy arm: authorized, and deliberately unattributed.
     *
     * Both halves are asserted because each carries a different half of the risk. `authorized`
     * being true is what makes this look like a live over-grant; `grantorPartyId` being absent is
     * the only reason it is not one. A test asserting either alone would stay green through the
     * change that matters.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `the legacy authorization arm answers authorized with no grantor and no delegation id`() {
        val accountId = openAccount()
        grantLegacyPaymentRole(accountId)

        val json = paymentAuthorization(accountId)

        assertThat(json.getString("outcome"))
            .describedAs("the row must be matched by the legacy disjunct, or this test proves nothing")
            .isEqualTo("LEGACY_AUTHORIZATION")
        assertThat(json.getBoolean("authorized"))
            .describedAs("the legacy store still answers yes — this is the half that looks like an over-grant")
            .isTrue()

        // `getString` cannot distinguish an absent key from an explicit JSON null, and the edge
        // treats both as a refusal — but only ABSENCE is what the resource is written to produce,
        // so assert the key is not in the object at all. A future change that emits an explicit
        // null would still be safe; one that emits a value must not pass unnoticed.
        val fields: Map<String, kotlin.Any?> = json.getMap("$")
        assertThat(fields)
            .describedAs(
                "grantorPartyId is the edge's debit gate: emitting it here puts the un-reconciled " +
                    "legacy store on the money path. Widen only after the ADR-0232 D1 dual-run exists.",
            )
            .doesNotContainKey("grantorPartyId")
        assertThat(fields).doesNotContainKey("delegationId")
    }

    // ── the positive control ───────────────────────────────────────────────────────────────

    /**
     * The control, and it is not optional: without it the assertion above passes just as happily
     * against a resource that never emits `grantorPartyId` for anything — including a broken
     * `DELEGATED` arm, which would be a customer-visible outage rather than an over-grant. This
     * proves the endpoint CAN name a grantor, so the omission above is a property of the legacy
     * arm and not of the endpoint.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-0000000000aa", roles = ["ROLE_OPERATOR"])
    fun `the delegated arm names the grant and the grantor`(): Unit = runBlocking {
        val accountId = openAccount()
        val source: InMemorySource<String> = connector.source("delegation-events-in")
        // Mirror the real Kafka connector's Vert.x delivery context — without it the suspend
        // @Incoming handler has no duplicated context for the Panache transaction.
        source.runOnVertxContext(true)
        source.send(delegationActivated(accountId))

        val json = awaitOutcome(accountId, "DELEGATED")

        assertThat(json.getBoolean("authorized")).isTrue()
        assertThat(json.getString("grantorPartyId")).isEqualTo(ownerParty.toString())
        assertThat(json.getString("delegationId")).isEqualTo(grantId.toString())
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

    private fun openAccount(): UUID {
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(
                """
                {"partyId":"$ownerParty","productId":"${UUID.randomUUID()}","accountType":"CURRENT",
                 "currencyCode":"CZK","legalName":"Legacy Arm IT"}
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

    /**
     * A real row in `account_authorizations`, written through the real `AuthorizationResource` —
     * the store ADR-0232 D1 retires, and the one still being written to today.
     *
     * `transactionLimit` is deliberately null: `withinLegacyTransactionLimit` treats a null as
     * "no ceiling", which is the shape of most rows and the unbounded case.
     */
    private fun grantLegacyPaymentRole(accountId: UUID) {
        Given {
            contentType("application/json")
            body(
                """
                {"partyId":"$delegateParty","role":"PAYMENT_ONLY","dailyLimit":null,"transactionLimit":null,
                 "validFrom":"${LocalDate.now()}","validTo":null,"grantedBy":"$operator"}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/accounts/$accountId/authorizations")
        } Then {
            statusCode(201)
        }
    }

    /** The exact question customer-edge asks before a domestic debit, with the amount. */
    private fun paymentAuthorization(accountId: UUID) = Given { this } When {
        get(
            "/api/v1/accounts/$accountId/delegation/payment-authorization" +
                "?partyId=$delegateParty&amount=1500.00&currency=CZK",
        )
    } Then {
        statusCode(200)
    } Extract {
        jsonPath()
    }

    /** The projection is event-fed, so the DELEGATED answer arrives asynchronously. */
    private suspend fun awaitOutcome(accountId: UUID, outcome: String): io.restassured.path.json.JsonPath {
        repeat(ATTEMPTS) {
            val json = paymentAuthorization(accountId)
            if (json.getString("outcome") == outcome) return json
            delay(POLL_MILLIS)
        }
        throw AssertionError("outcome never became $outcome — the projection did not converge")
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
