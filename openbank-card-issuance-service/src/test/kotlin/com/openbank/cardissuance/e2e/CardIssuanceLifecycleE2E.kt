// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.e2e

import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journeys of the card lifecycle over the real HTTP surface against real Postgres +
 * Valkey. Card issuance is money-path and PCI-shaped, so the E2E pins what a unit test around the
 * use case cannot see:
 *
 *  - **The issue-to-active spine**: a plastic DEBIT card is born PENDING (ADR: plastic must be
 *    received before it can transact), replays idempotently on its `Idempotency-Key`, and walks
 *    activate → suspend → resume → block → cancel with every write attributed to `X-Operator-Id`.
 *    Cancel is terminal.
 *  - **Controls that actually control (the policy order)**: the authorisation endpoint decides
 *    against status first, then channel toggles, then abroad, then category rules, then limits —
 *    so the decline reason the customer sees is the one they can act on. A VIRTUAL card has no
 *    plastic, so it is born ACTIVE and can be decided against immediately.
 *  - **PCI boundary**: the synthetic PAN/CVV read is machine-only — the named-caller guard
 *    (#10486, mirror of `service-edge-card-details-read` in the rego bundle) admits the verified
 *    customer-edge service account and refuses a human operator even holding ROLE_OPERATOR, and
 *    the response carries `Cache-Control: no-store`. Product-catalog entitlements degrade to the
 *    documented permissive FALLBACK (maxCards `-1` = "unknown cap", not "nothing left") when the
 *    catalog does not answer.
 *  - **Input guards as client errors, not 500s**: an absent `Idempotency-Key` on issue is 400
 *    (#3624 — the `suspend` NPE trap), an absent `X-Operator-Id` on a lifecycle write is 400, an
 *    out-of-range party-list `limit` is 400, an unknown authorisation channel is 400
 *    `CHANNEL_UNKNOWN`, and `ROLE_VIEWER` is refused 403 on issue.
 *
 * Reads that carry the named-caller guard (`GET /{id}`, `GET /party/{id}`) run under a synthetic
 * interactive identity (`azp=openbank-admin-ui`), because `ServiceAccountIdentity.isHumanStaff`
 * requires a real JWT claim set — plain `@TestSecurity` is not a JsonWebToken. Outgoing Kafka is
 * in-memory by design: the outbox row is the durable observable (asserted in the outbox ITs), no
 * broker is claimed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@QuarkusTestResource(CardIssuanceLifecycleE2E.NoDispatchInMemoryKafkaResource::class)
@Suppress("TooManyFunctions") // one REST journey helper per operation family (issue/lifecycle/read/decide/put)
class CardIssuanceLifecycleE2E {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("card-events-out") +
                mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-subject-1"),
            Claim(key = "preferred_username", value = "e2e-operator-1"),
        ],
    )
    fun `a plastic debit card walks issue activate suspend block and cancel`() {
        val key = "card-e2e-${UUID.randomUUID()}"
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        val created = issueCard(key, debitBody(partyId, accountId))
        val id = UUID.fromString(created["id"] as String)
        // Plastic exists for one reason: somebody has to receive it. Born PENDING, never ACTIVE.
        assertThat(created["status"]).isEqualTo("PENDING")
        assertThat(created["activatedAt"]).isNull()
        assertThat(created["maskedPan"] as String).contains("*")
        assertThat((created["maskedPan"] as String).takeLast(4)).matches("\\d{4}")
        assertThat(LocalDate.parse(created["expiryDate"] as String).year).isEqualTo(LocalDate.now().year + 4)

        // Same key replays the stored card — one row, the original PAN, never a second card.
        val replay = issueCard(key, debitBody(partyId, accountId))
        assertThat(replay["id"]).isEqualTo(id.toString())
        assertThat(replay["maskedPan"]).isEqualTo(created["maskedPan"])

        val activated = lifecycle(id, "activate", "e2e-operator-1").jsonPath().getMap<String, Any?>("")
        assertThat(activated["status"]).isEqualTo("ACTIVE")
        assertThat(activated["activatedAt"]).isNotNull()

        // A suspended card declines authorisation — status is checked before every control.
        val suspended = lifecycle(id, "suspend", "e2e-operator-1").jsonPath().getMap<String, Any?>("")
        assertThat(suspended["status"]).isEqualTo("SUSPENDED")
        val declinedWhileSuspended = authorize(id, mapOf("amountMinorUnits" to 1000, "channel" to "CONTACTLESS"))
        assertThat(declinedWhileSuspended["approved"]).isEqualTo(false)
        assertThat(declinedWhileSuspended["declineReason"]).isEqualTo("CARD_NOT_ACTIVE")

        val resumed = lifecycle(id, "resume", "e2e-operator-1").jsonPath().getMap<String, Any?>("")
        assertThat(resumed["status"]).isEqualTo("ACTIVE")

        val blocked = lifecycle(id, "block", "e2e-operator-1", mapOf("reason" to "reported lost"))
            .jsonPath().getMap<String, Any?>("")
        assertThat(blocked["status"]).isEqualTo("BLOCKED")
        assertThat(blocked["blockedReason"]).isEqualTo("reported lost")
        // activate() requires PENDING — a blocked card must not be reactivatable through it.
        lifecycle(id, "activate", "e2e-operator-1", expected = 400)

        // The PCI boundary for staff: even ROLE_OPERATOR is not a reason to render a PAN.
        Given {
            this
        } When {
            get("/api/v1/cards/$id/secure-details")
        } Then {
            statusCode(403)
        }

        val cancelled = lifecycle(id, "cancel", "e2e-operator-1", mapOf("reason" to "customer closed account"))
            .jsonPath().getMap<String, Any?>("")
        assertThat(cancelled["status"]).isEqualTo("CANCELLED")
        // Cancel is terminal: every further lifecycle write refuses as a client error, not a 500.
        lifecycle(id, "suspend", "e2e-operator-1", expected = 400)

        val readBack = getCard(id).jsonPath().getMap<String, Any?>("")
        assertThat(readBack["status"]).isEqualTo("CANCELLED")
        assertThat(readBack["maskedPan"]).isEqualTo(created["maskedPan"])
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a virtual card is active on issue and its controls and limits decide authorizations`() {
        val created = issueVirtualCard("E2E-VIRTUAL", "E2E Virtual Holder", "MASTERCARD")
        val id = UUID.fromString(created["id"] as String)
        // No plastic: born ACTIVE, the only PENDING→ACTIVE route being operator-only would
        // otherwise leave a self-issued virtual card dead on arrival.
        assertThat(created["status"]).isEqualTo("ACTIVE")
        assertThat(created["activatedAt"]).isNotNull()

        val approved = authorize(id, mapOf("amountMinorUnits" to 1000, "channel" to "CONTACTLESS"))
        assertThat(approved["approved"]).isEqualTo(true)
        assertThat(approved["declineReason"]).isNull()

        // Channel toggle off → the ONLINE rail declines, with the reason the customer can act on.
        putCard(
            id,
            "controls",
            mapOf(
                "contactlessEnabled" to true,
                "onlineEnabled" to false,
                "atmEnabled" to true,
                "abroadEnabled" to true,
            ),
        )
        val channelDeclined = authorize(id, mapOf("amountMinorUnits" to 1000, "channel" to "ONLINE"))
        assertThat(channelDeclined["declineReason"]).isEqualTo("CHANNEL_DISABLED")

        // Abroad toggle off → a foreign-presented transaction declines (CZ is the home market).
        putCard(
            id,
            "controls",
            mapOf(
                "contactlessEnabled" to true,
                "onlineEnabled" to true,
                "atmEnabled" to true,
                "abroadEnabled" to false,
            ),
        )
        val abroadDeclined = authorize(
            id,
            mapOf(
                "amountMinorUnits" to 1000,
                "channel" to "CONTACTLESS",
                "countryCode" to "DE",
            ),
        )
        assertThat(abroadDeclined["declineReason"]).isEqualTo("ABROAD_DISABLED")

        // Limits: the card's own ceilings decide last, after every customer-facing control.
        putCard(id, "limits", mapOf("dailyLimitMinorUnits" to 1000, "monthlyLimitMinorUnits" to 5000))
        val overDaily = authorize(id, mapOf("amountMinorUnits" to 2000, "channel" to "CONTACTLESS"))
        assertThat(overDaily["declineReason"]).isEqualTo("DAILY_LIMIT_EXCEEDED")

        // An unparseable channel is a client error naming the problem, not a silently dropped check.
        assertUnknownChannelIsRejected(id)

        // A category block the customer set decides before the card's ceilings: gambling off.
        putCard(id, "category-limits", mapOf("limits" to listOf(mapOf("category" to "GAMBLING", "blocked" to true))))
        val gamblingDeclined = authorize(
            id,
            mapOf(
                "amountMinorUnits" to 500,
                "channel" to "CONTACTLESS",
                "mcc" to "7995",
            ),
        )
        assertThat(gamblingDeclined["declineReason"]).isEqualTo("CATEGORY_BLOCKED")
        // An unmapped MCC still goes through the card-level check and approves under the daily cap.
        val mapped = authorize(id, mapOf("amountMinorUnits" to 500, "channel" to "CONTACTLESS", "mcc" to "5411"))
        assertThat(mapped["approved"]).isEqualTo(true)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "synthetic-subject"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `secure details are edge only and entitlements degrade to the catalog fallback`() {
        val partyId = UUID.randomUUID()
        val created = issueVirtualCardForParty(partyId, "E2E-SECURE", "E2E Edge Holder", "VISA")
        val id = UUID.fromString(created["id"] as String)

        // The verified customer-edge service account decrypts the synthetic PAN exactly once.
        val details: Response = Given {
            this
        } When {
            get("/api/v1/cards/$id/secure-details")
        } Then {
            statusCode(200)
            header("Cache-Control", "no-store")
        } Extract {
            response()
        }
        val body = details.jsonPath().getMap<String, Any?>("")
        assertThat(body["pan"] as String).matches("\\d{12,19}")
        assertThat(body["cvv"] as String).matches("\\d{3}")
        assertThat(body["cardholderName"]).isEqualTo("E2E Edge Holder")

        // Entitlements with the catalog unreachable degrade to the documented permissive fallback:
        // maxCards/remaining are -1 ("no known cap"), never 0 ("nothing left").
        val entitlements: Map<String, Any?> = Given {
            this
        } When {
            get("/api/v1/cards/party/$partyId/entitlements?productCode=E2E-SECURE")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(entitlements["productCode"]).isEqualTo("E2E-SECURE")
        assertThat(entitlements["maxCards"] as Int).isEqualTo(-1)
        assertThat(entitlements["source"]).isEqualTo("FALLBACK")
        assertThat(entitlements["issued"] as Int).isGreaterThanOrEqualTo(1)

        // productCode is a required query parameter — absent is a client error, not a 500.
        Given {
            this
        } When {
            get("/api/v1/cards/party/$partyId/entitlements")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-subject-1"),
            Claim(key = "preferred_username", value = "e2e-operator-1"),
        ],
    )
    fun `input guards stay client errors and unknown cards read back 404`() {
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        // #3624: an ABSENT Idempotency-Key header must be 400 — the suspend-NPE trap shipped
        // exactly this as a 500. A blank header is also 400.
        issueCard(null, debitBody(partyId, accountId), expected = 400)
        issueCard(" ", debitBody(partyId, accountId), expected = 400)

        val created = issueCard("card-e2e-${UUID.randomUUID()}", debitBody(partyId, accountId))
        val id = UUID.fromString(created["id"] as String)

        // Every lifecycle write is audit-attributed to X-Operator-Id; absent is a client error.
        Given {
            contentType("application/json")
            body(emptyMap<String, Any?>())
        } When {
            post("/api/v1/cards/$id/activate")
        } Then {
            statusCode(400)
        }

        // The party-list limit is bounded (1..200); outside the range is a client error.
        Given {
            this
        } When {
            get("/api/v1/cards/party/$partyId?limit=201")
        } Then {
            statusCode(400)
        }

        getCard(UUID.randomUUID(), expected = 404)
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewer reads the list but is refused 403 on issue`() {
        val listed: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/cards")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(listed).isNotNull()

        issueCard(
            "card-e2e-${UUID.randomUUID()}",
            debitBody(UUID.randomUUID(), UUID.randomUUID()),
            expected = 403,
        )
    }

    // ---------- REST journey helpers (kept out of the tests so each journey reads as a story) ----------

    private fun issueVirtualCard(productCode: String, holder: String, network: String): Map<String, Any?> =
        issueVirtualCardForParty(UUID.randomUUID(), productCode, holder, network)

    private fun issueVirtualCardForParty(
        partyId: UUID,
        productCode: String,
        holder: String,
        network: String,
    ): Map<String, Any?> = issueCard(
        "card-e2e-${UUID.randomUUID()}",
        mapOf(
            "partyId" to partyId.toString(),
            "accountId" to UUID.randomUUID().toString(),
            "productCode" to productCode,
            "cardType" to "VIRTUAL",
            "network" to network,
            "cardholderName" to holder,
            "embossedName" to holder.uppercase(),
            "currency" to "CZK",
        ),
    )

    private fun debitBody(partyId: UUID, accountId: UUID) = mapOf(
        "partyId" to partyId.toString(),
        "accountId" to accountId.toString(),
        "productCode" to "E2E-DEBIT",
        "cardType" to "DEBIT",
        "network" to "VISA",
        "cardholderName" to "E2E Holder",
        "embossedName" to "E2E HOLDER",
        "currency" to "CZK",
        "deliveryAddress" to "Testovaci 1, Praha",
    )

    private fun issueCard(key: String?, body: Map<String, Any?>, expected: Int = 201): Map<String, Any?> = Given {
        contentType("application/json")
        if (key != null) header("Idempotency-Key", key)
        body(body)
    } When {
        post("/api/v1/cards")
    } Then {
        statusCode(expected)
    } Extract {
        // Error responses (403 has no entity at all) carry no card to read back.
        if (expected == 201) jsonPath().getMap("") else emptyMap()
    }

    private fun lifecycle(
        id: UUID,
        action: String,
        operatorId: String,
        body: Map<String, Any?> = emptyMap(),
        expected: Int = 200,
    ): Response = Given {
        contentType("application/json")
        header("X-Operator-Id", operatorId)
        body(body)
    } When {
        post("/api/v1/cards/$id/$action")
    } Then {
        statusCode(expected)
    } Extract {
        response()
    }

    private fun putCard(id: UUID, action: String, body: Map<String, Any?>, expected: Int = 200): Response = Given {
        contentType("application/json")
        header("X-Operator-Id", "e2e-operator-1")
        body(body)
    } When {
        put("/api/v1/cards/$id/$action")
    } Then {
        statusCode(expected)
    } Extract {
        response()
    }

    private fun getCard(id: UUID, expected: Int = 200): Response = Given {
        this
    } When {
        get("/api/v1/cards/$id")
    } Then {
        statusCode(expected)
    } Extract {
        response()
    }

    private fun assertUnknownChannelIsRejected(id: UUID) {
        Given {
            contentType("application/json")
            body(mapOf("amountMinorUnits" to 100, "channel" to "teleport"))
        } When {
            post("/api/v1/cards/$id/authorizations")
        } Then {
            statusCode(400)
        }
    }

    private fun authorize(id: UUID, body: Map<String, Any?>): Map<String, Any?> = Given {
        contentType("application/json")
        body(body)
    } When {
        post("/api/v1/cards/$id/authorizations")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getMap("")
    }
}
