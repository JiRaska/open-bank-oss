// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.e2e

import com.openbank.cardprocessing.application.port.out.CardIssuancePolicyPort
import com.openbank.cardprocessing.application.port.out.CardLookupPort
import com.openbank.cardprocessing.application.port.out.CardOwnership
import com.openbank.cardprocessing.application.port.out.IssuerDecision
import com.openbank.cardprocessing.domain.model.CountedSpend
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.cardprocessing.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journeys of the card transaction spine — authorise, clear, reverse — over the real
 * HTTP surface against real Postgres. The money-path invariants the API ITs prove piece by piece
 * are pinned here as one continuous customer-visible story:
 *
 *  - **An approval is a hold, not a charge.** `201` carries `APPROVED` with the full amount in
 *    `heldAmountMinorUnits` and nothing cleared; the idempotent replay of the same key returns
 *    the same authorisation and never a second hold.
 *  - **Clearing draws the hold down, once per key.** A partial presentment leaves the deal
 *    `PARTIALLY_CLEARED` with `held = authorised − cleared`; replaying the same clearing key
 *    changes nothing (no second hold decrement, no second debit); a presentment above the
 *    remaining hold is refused `409 EXCEEDS_AUTHORIZED_AMOUNT`; reversal releases the remainder.
 *  - **A decline is a recorded decision that holds nothing** — `201` with `DECLINED`, the issuer
 *    reason echoed, `heldAmountMinorUnits` 0 — and a presentment against it is refused
 *    `409 NOT_HOLDING_FUNDS`. Answering 4xx for a decline would make "the card was refused"
 *    indistinguishable from "the request was malformed" to every acquirer.
 *  - **Guards stay client errors**: a missing or blank `Idempotency-Key` is 400, a currency that
 *    is not the card's currency is 400, a card card-issuance does not know is 404 CARD_NOT_FOUND,
 *    and `ROLE_VIEWER` is refused 403.
 *
 * The issuer wire is the [StubIssuer] alternative — the decision belongs to card-issuance and
 * its real contract is pinned by the pact suite; here it is fixed so the spine's own arithmetic
 * (hold, partial clearing, release) is what varies. Outbox dispatch is disabled: the outbox row
 * is asserted in the outbox ITs, no broker is claimed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(CardProcessingLifecycleE2E.Profile::class)
class CardProcessingLifecycleE2E {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(StubIssuer::class.java)
    }

    /** Literal ids — a profile loads in another classloader, so a random companion value would differ. */
    @Alternative
    @ApplicationScoped
    class StubIssuer :
        CardLookupPort,
        CardIssuancePolicyPort {
        override suspend fun lookup(cardId: UUID): CardOwnership? =
            if (cardId == KNOWN_CARD) CardOwnership(ACCOUNT, PARTY, "CZK") else null

        // The ceiling makes one stub cover both outcomes: small amounts approve, large ones decline.
        override suspend fun decide(
            cardId: UUID,
            amountMinorUnits: Long,
            channel: PresentmentChannel,
            mcc: String?,
            countryCode: String?,
            counted: CountedSpend,
        ): IssuerDecision = if (amountMinorUnits <= APPROVAL_CEILING) {
            IssuerDecision(approved = true, reason = null, category = "GROCERIES")
        } else {
            IssuerDecision(approved = false, reason = "DAILY_LIMIT_EXCEEDED", category = "GROCERIES")
        }

        companion object {
            val KNOWN_CARD: UUID = UUID.fromString("1e1e1e1e-2a3a-4b4b-8c5c-6d6d6d6d6d6d")
            val ACCOUNT: UUID = UUID.fromString("8a8a8a8a-9b9b-4c0c-8d1d-2e2e2e2e2e2e")
            val PARTY: UUID = UUID.fromString("5a5a5a5a-6b6b-4c7c-8d8d-9e9e9e9e9e9e")
            val UNKNOWN_CARD: UUID = UUID.fromString("9f9f9f9f-0a1a-4b2b-8c3d-4d4d4d4d4d4d")
            const val APPROVAL_CEILING = 100_000L
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `an approved authorisation holds the amount and replays idempotently`() {
        val key = "cp-e2e-${UUID.randomUUID()}"
        val created = authorize(50_000, "CZK", key)
        val id = created["id"] as String
        assertThat(created["status"]).isEqualTo("APPROVED")
        assertThat(created["amountMinorUnits"] as Int).isEqualTo(50_000)
        assertThat(created["currencyCode"]).isEqualTo("CZK")
        assertThat(created["heldAmountMinorUnits"] as Int).isEqualTo(50_000)
        assertThat(created["clearedAmountMinorUnits"] as Int).isEqualTo(0)
        assertThat(created["declineReason"]).isNull()
        assertThat(created["category"]).isEqualTo("GROCERIES")

        // Same key: the stored authorisation replays — one hold, never two.
        val replay = authorize(50_000, "CZK", key)
        assertThat(replay["id"]).isEqualTo(id)
        assertThat(replay["heldAmountMinorUnits"]).isEqualTo(50_000)

        val readBack = given().`when`().get("/api/v1/card-authorizations/$id")
            .then().statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("heldAmountMinorUnits", equalTo(50_000))
            .extract().body().jsonPath().getMap<String, Any?>("")
        assertThat(readBack["accountId"]).isEqualTo(StubIssuer.ACCOUNT.toString())

        given().`when`().get("/api/v1/card-authorizations/card/${StubIssuer.KNOWN_CARD}")
            .then().statusCode(200)
            // The replay above created no second record: one authorisation for this card.
            .body("count", equalTo(1))
            .body("data.id", hasItem(id))
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `clearing draws the hold down once per key and reversal releases the remainder`() {
        val id = authorize(50_000, "CZK", "cp-e2e-${UUID.randomUUID()}")["id"] as String

        val clearingKey = "cp-clear-${UUID.randomUUID()}"
        given().contentType("application/json").header("Idempotency-Key", clearingKey)
            .body("""{"amountMinorUnits":20000,"currencyCode":"CZK"}""".trimIndent())
            .`when`().post("/api/v1/card-authorizations/$id/clearing")
            .then().statusCode(200)
            .body("status", equalTo("PARTIALLY_CLEARED"))
            .body("clearedAmountMinorUnits", equalTo(20_000))
            .body("heldAmountMinorUnits", equalTo(30_000))

        // Replaying the SAME clearing key changes nothing: no second hold decrement, no second debit.
        given().contentType("application/json").header("Idempotency-Key", clearingKey)
            .body("""{"amountMinorUnits":20000,"currencyCode":"CZK"}""".trimIndent())
            .`when`().post("/api/v1/card-authorizations/$id/clearing")
            .then().statusCode(200)
            .body("status", equalTo("PARTIALLY_CLEARED"))
            .body("clearedAmountMinorUnits", equalTo(20_000))
            .body("heldAmountMinorUnits", equalTo(30_000))

        // Above the remaining hold the presentment is refused, and the hold is untouched.
        given().contentType("application/json").header("Idempotency-Key", "cp-clear-${UUID.randomUUID()}")
            .body("""{"amountMinorUnits":40000,"currencyCode":"CZK"}""".trimIndent())
            .`when`().post("/api/v1/card-authorizations/$id/clearing")
            .then().statusCode(409)
            .body("reason", equalTo("EXCEEDS_AUTHORIZED_AMOUNT"))

        // Reversal while still holding funds releases the remainder; what cleared stays cleared.
        val reversed = given().`when`().post("/api/v1/card-authorizations/$id/reversal")
            .then().statusCode(200)
            .body("status", equalTo("REVERSED"))
            .body("heldAmountMinorUnits", equalTo(0))
            .extract().body().jsonPath().getMap<String, Any?>("")
        assertThat(reversed["clearedAmountMinorUnits"]).isEqualTo(20_000)

        // Terminal: nothing may draw on it any more.
        given().contentType("application/json").header("Idempotency-Key", "cp-clear-${UUID.randomUUID()}")
            .body("""{"amountMinorUnits":1,"currencyCode":"CZK"}""".trimIndent())
            .`when`().post("/api/v1/card-authorizations/$id/clearing")
            .then().statusCode(409)
            .body("reason", equalTo("NOT_HOLDING_FUNDS"))
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a full clear closes the authorisation out and leaves nothing to reverse`() {
        val id = authorize(50_000, "CZK", "cp-e2e-${UUID.randomUUID()}")["id"] as String

        given().contentType("application/json").header("Idempotency-Key", "cp-clear-${UUID.randomUUID()}")
            .body("""{"amountMinorUnits":50000,"currencyCode":"CZK"}""".trimIndent())
            .`when`().post("/api/v1/card-authorizations/$id/clearing")
            .then().statusCode(200)
            .body("status", equalTo("CLEARED"))
            .body("heldAmountMinorUnits", equalTo(0))

        // CLEARED is terminal: the hold is gone, so there is nothing for a reversal to release.
        given().`when`().post("/api/v1/card-authorizations/$id/reversal")
            .then().statusCode(409)
            .body("reason", equalTo("NOT_HOLDING_FUNDS"))
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a decline is a recorded decision that holds nothing`() {
        // A decline is still a 201 — a recorded decision, not a malformed request.
        val id = given().contentType("application/json").header("Idempotency-Key", "cp-e2e-${UUID.randomUUID()}")
            .body(authorizeBody(200_000, "CZK"))
            .`when`().post("/api/v1/card-authorizations")
            .then().statusCode(201)
            .body("status", equalTo("DECLINED"))
            .body("declineReason", equalTo("DAILY_LIMIT_EXCEEDED"))
            .body("heldAmountMinorUnits", equalTo(0))
            .extract().body().jsonPath().getString("id")
        val declined = given().`when`().get("/api/v1/card-authorizations/$id")
            .then().statusCode(200)
            .body("status", equalTo("DECLINED"))
            .body("declineReason", equalTo("DAILY_LIMIT_EXCEEDED"))
            .body("heldAmountMinorUnits", equalTo(0))
            .extract().body().jsonPath().getMap<String, Any?>("")
        assertThat(declined["clearedAmountMinorUnits"]).isEqualTo(0)

        // A presentment against a decline has nothing to draw on.
        given().contentType("application/json").header("Idempotency-Key", "cp-clear-${UUID.randomUUID()}")
            .body("""{"amountMinorUnits":100,"currencyCode":"CZK"}""".trimIndent())
            .`when`().post("/api/v1/card-authorizations/$id/clearing")
            .then().statusCode(409)
            .body("reason", equalTo("NOT_HOLDING_FUNDS"))
        given().`when`().post("/api/v1/card-authorizations/$id/reversal")
            .then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `guards stay client errors from malformed request to unknown card`() {
        // A missing Idempotency-Key is 400 — the suspend-NPE trap (#3624) this guard was written for.
        given().contentType("application/json")
            .body(authorizeBody(1_000, "CZK"))
            .`when`().post("/api/v1/card-authorizations")
            .then().statusCode(400)
        given().contentType("application/json").header("Idempotency-Key", " ")
            .body(authorizeBody(1_000, "CZK"))
            .`when`().post("/api/v1/card-authorizations")
            .then().statusCode(400)

        // The currency must be the card's currency.
        given().contentType("application/json").header("Idempotency-Key", "cp-e2e-${UUID.randomUUID()}")
            .body(authorizeBody(1_000, "EUR"))
            .`when`().post("/api/v1/card-authorizations")
            .then().statusCode(400)

        // A card card-issuance does not know is a 404, not a 500.
        given().contentType("application/json").header("Idempotency-Key", "cp-e2e-${UUID.randomUUID()}")
            .body(
                """
                {"cardId":"${StubIssuer.UNKNOWN_CARD}","amountMinorUnits":1000,"currencyCode":"CZK","channel":"CONTACTLESS"}
                """.trimIndent(),
            )
            .`when`().post("/api/v1/card-authorizations")
            .then().statusCode(404)
            .body("error", equalTo("CARD_NOT_FOUND"))

        // An authorisation that does not exist reads back 404.
        given().`when`().get("/api/v1/card-authorizations/${UUID.randomUUID()}")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewer is refused 403 on the processing surface`() {
        given().`when`().get("/api/v1/card-authorizations/card/${StubIssuer.KNOWN_CARD}")
            .then().statusCode(403)
    }

    // ---------- REST journey helpers ----------

    private fun authorizeBody(amount: Long, currency: String) = """
        {"cardId":"${StubIssuer.KNOWN_CARD}","amountMinorUnits":$amount,"currencyCode":"$currency","channel":"CONTACTLESS","mcc":"5411"}
    """.trimIndent()

    private fun authorize(amount: Long, currency: String, key: String): Map<String, Any?> = given()
        .contentType("application/json").header("Idempotency-Key", key)
        .body(authorizeBody(amount, currency))
        .`when`().post("/api/v1/card-authorizations")
        .then().statusCode(201)
        .body("status", equalTo("APPROVED"))
        .extract().body().jsonPath().getMap("")
}
