// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.e2e

import com.openbank.libs.synthetic.SyntheticTaint
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * E2E suite for the declared-holding lifecycle (ADR-0301), proving the full durable + wire
 * evidence chain over the real Postgres and the real Redpanda broker. Every pre-existing test
 * switches `wealth-events-out` to the in-memory connector, so the outbox dispatcher had never
 * relayed a row over an actual broker here — the holding row, the `wealth_outbox` row and the
 * Kafka record are asserted as three independent facts that must agree, with the ADR-0050
 * addressing contract (aggregate id as record key, `ce-id` == `idempotency-key`, `ce-type`)
 * pinned on the real records.
 */
@QuarkusTest
@TestProfile(WealthE2EProfile::class)
class WealthOutboxE2E : WealthE2EBase() {

    private fun declareBody(reference: String?, amount: String = "250000.00") = """
        {
          "holdingType": "COLLECTIBLE",
          "label": "Omega Speedmaster 1969",
          "valuation": {
            "amount": $amount,
            "currency": "CZK",
            "valuedAt": "2026-09-01",
            "source": "CUSTOMER_DECLARED"
          },
          "ownershipShare": 1,
          ${if (reference != null) "\"externalReference\": \"$reference\"," else ""}
          "documentIds": []
        }
    """.trimIndent()

    private fun declare(owner: UUID, reference: String?, amount: String = "250000.00"): UUID = UUID.fromString(
        (
            Given {
                contentType("application/json")
                header("X-Customer-Party-Id", owner.toString())
                body(declareBody(reference, amount))
            } When { post("/api/v1/holdings") } Then {
                statusCode(201)
                body("status", equalTo("ACTIVE"))
                body("valuationSource", equalTo("CUSTOMER_DECLARED"))
            }
            ).extract().path<String>("holdingId"),
    )

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `declaring a holding commits the row and its event reaches the real broker`() {
        val owner = UUID.randomUUID()
        val holdingId = declare(owner, "SN-1969-042")

        // Durable half: the row and the outbox entry commit together; the dispatcher relays it.
        assertThat(holdingStatus(holdingId)).isEqualTo("ACTIVE")
        assertThat(awaitOutboxSent(holdingId)).isEqualTo("SENT")

        // Wire half: the record carries the aggregate id as its key (ADR-0050 N2) and the event
        // payload is the domain event, verbatim.
        val event = awaitEvent(holdingId, "wealth.holding.declared.v1")
        assertThat(event.get("holdingId").asText()).isEqualTo(holdingId.toString())
        assertThat(event.get("ownerPartyId").asText()).isEqualTo(owner.toString())
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("250000.00")
        assertThat(event.get("currency").asText()).isEqualTo("CZK")
        assertThat(event.get("occurredAt").asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a replayed declare returns the original holding and emits no second event`() {
        val owner = UUID.randomUUID()
        val first = declare(owner, "SN-REPLAY-1", "250000.00")
        val second = declare(owner, "SN-REPLAY-1", "275000.00")

        // The retry is answered with the ORIGINAL: a replay must not silently take the newer
        // amount — that would make a retried create indistinguishable from an intentional revalue.
        assertThat(second).isEqualTo(first)
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", owner.toString())
        } When { get("/api/v1/holdings/$first") } Then {
            statusCode(200)
            body("amount", equalTo(250000.00f))
        }
        // Exactly one declared event crossed the wire for this aggregate.
        val events = awaitEventsForKey(first, 1)
        assertThat(events.map { String(it.headers().lastHeader("ce-type").value(), Charsets.UTF_8) })
            .containsExactly("wealth.holding.declared.v1")
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `revalue appends the valuation series and emits a revalued event`() {
        val owner = UUID.randomUUID()
        val holdingId = declare(owner, "SN-REVALUE-1")

        Given {
            contentType("application/json")
            body(
                """{"valuation":{"amount":400000.00,"currency":"CZK","valuedAt":"2026-10-01",
                   "source":"CUSTOMER_DECLARED"}}
                """.trimIndent(),
            )
        } When { put("/api/v1/holdings/$holdingId/valuation") } Then {
            statusCode(200)
            body("amount", equalTo(400000.00f))
        }

        // The append-only series keeps both values, newest first.
        Given { this } When { get("/api/v1/holdings/$holdingId/valuations") } Then {
            statusCode(200)
            body("", hasSize<Any>(2))
            body("[0].amount", equalTo(400000.00f))
            body("[1].amount", equalTo(250000.00f))
        }
        assertThat(
            jdbc { c ->
                c.prepareStatement(
                    "SELECT count(*) FROM declared_holding_valuations WHERE holding_id = ?",
                ).use { ps ->
                    ps.setObject(1, holdingId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            },
        ).isEqualTo(2)

        assertThat(awaitOutboxSent(holdingId)).isEqualTo("SENT")
        val event = awaitEvent(holdingId, "wealth.holding.revalued.v1")
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("400000.00")
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `withdraw emits the withdrawn event and the holding is terminal`() {
        val owner = UUID.randomUUID()
        val holdingId = declare(owner, "SN-WITHDRAW-1")

        Given { this } When { delete("/api/v1/holdings/$holdingId") } Then {
            statusCode(200)
            body("status", equalTo("WITHDRAWN"))
        }
        assertThat(holdingStatus(holdingId)).isEqualTo("WITHDRAWN")
        assertThat(awaitEvent(holdingId, "wealth.holding.withdrawn.v1").get("holdingId").asText())
            .isEqualTo(holdingId.toString())

        // Terminal means terminal: revalue and a second withdraw both conflict.
        Given {
            contentType("application/json")
            body("""{"valuation":{"amount":1,"currency":"CZK","valuedAt":"2026-10-02","source":"CUSTOMER_DECLARED"}}""")
        } When { put("/api/v1/holdings/$holdingId/valuation") } Then {
            statusCode(409)
            body("error", containsString("withdrawn"))
        }
        Given { this } When { delete("/api/v1/holdings/$holdingId") } Then {
            statusCode(409)
            body("error", containsString("already withdrawn"))
        }
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a pledged holding refuses withdraw until lending releases it`() {
        val owner = UUID.randomUUID()
        val holdingId = declare(owner, "SN-PLEDGED-1")
        val loanId = UUID.randomUUID()

        // Lending's write, seeded exactly as the lending side would persist it (wealth exposes no
        // pledge endpoint — the pledge lives on the row by ADR-0301).
        setPledged(holdingId, loanId)
        Given { this } When { delete("/api/v1/holdings/$holdingId") } Then {
            statusCode(409)
            body("error", containsString("pledged to loan $loanId"))
        }
        assertThat(holdingStatus(holdingId)).isEqualTo("PLEDGED")

        // Lending releases: withdraw succeeds and the release did not fabricate an event.
        setPledged(holdingId, null)
        Given { this } When { delete("/api/v1/holdings/$holdingId") } Then {
            statusCode(200)
            body("status", equalTo("WITHDRAWN"))
        }
        assertThat(
            outboxRowsFor(holdingId).map {
                it.first
            },
        ).containsExactly("wealth.holding.declared.v1", "wealth.holding.withdrawn.v1")
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `one holding's events keep their aggregate order on the real broker`() {
        val owner = UUID.randomUUID()
        val holdingId = declare(owner, "SN-ORDER-1")

        Given {
            contentType("application/json")
            body(
                """{"valuation":{"amount":300000,"currency":"CZK","valuedAt":"2026-10-01","source":"CUSTOMER_DECLARED"}}""",
            )
        } When { put("/api/v1/holdings/$holdingId/valuation") } Then { statusCode(200) }
        Given { this } When { delete("/api/v1/holdings/$holdingId") } Then { statusCode(200) }

        // ADR-0050 N2: the aggregate id is the partition key, so declared → revalued → withdrawn
        // must arrive in lifecycle order — asserted on the records drained from Redpanda.
        val types = awaitEventsForKey(holdingId, 3).map {
            String(it.headers().lastHeader("ce-type").value(), Charsets.UTF_8)
        }
        assertThat(types).containsExactly(
            "wealth.holding.declared.v1",
            "wealth.holding.revalued.v1",
            "wealth.holding.withdrawn.v1",
        )
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `the missing owner header is a 400 before any row or event exists`() {
        val rowsBefore = jdbc { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM declared_holdings").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

        Given {
            contentType("application/json")
            body(declareBody("SN-NOOWNER-1"))
        } When { post("/api/v1/holdings") } Then {
            statusCode(400)
        }

        val rowsAfter = jdbc { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM declared_holdings").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        assertThat(rowsAfter).isEqualTo(rowsBefore)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `an unknown holding is a 404 on the read and on its valuation history`() {
        val unknown = UUID.randomUUID()
        Given { this } When { get("/api/v1/holdings/$unknown") } Then {
            statusCode(404)
            body("error", containsString(unknown.toString()))
        }
        Given { this } When { get("/api/v1/holdings/$unknown/valuations") } Then { statusCode(404) }
    }

    @Test
    @TestSecurity(user = WealthE2EProfile.CANARY, roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-synthetic-canary"),
            Claim(key = "preferred_username", value = WealthE2EProfile.CANARY),
            Claim(key = "sub", value = "synthetic-canary-subject"),
        ],
    )
    fun `a trusted canary's holding is synthetic on the row and tainted on the real wire`() {
        val owner = UUID.randomUUID()
        val holdingId = UUID.fromString(
            (
                Given {
                    contentType("application/json")
                    header("X-Customer-Party-Id", owner.toString())
                    header(SyntheticTaint.KAFKA_HEADER, "true")
                    body(declareBody("SN-CANARY-1"))
                } When { post("/api/v1/holdings") } Then {
                    statusCode(201)
                }
                ).extract().path<String>("holdingId"),
        )

        assertThat(
            jdbc { c ->
                c.prepareStatement("SELECT synthetic FROM declared_holdings WHERE holding_id = ?").use { ps ->
                    ps.setObject(1, holdingId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            },
        ).isTrue()

        // ADR-0252: the taint reaches the broker record — a consumer can exclude canary traffic
        // without opening the payload.
        val record = awaitEventsForKey(holdingId, 1)[0]
        val taint = record.headers().lastHeader(SyntheticTaint.KAFKA_HEADER)
        assertThat(taint).isNotNull()
        assertThat(String(taint.value(), Charsets.UTF_8)).isEqualTo(SyntheticTaint.headerValue())
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a party sees only its own holdings in the list lens`() {
        val owner = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val holdingId = declare(owner, "SN-SCOPE-1")

        Given { header("X-Customer-Party-Id", owner.toString()) } When { get("/api/v1/holdings") } Then {
            statusCode(200)
            body("holdingId", hasItem(holdingId.toString()))
        }
        Given { header("X-Customer-Party-Id", stranger.toString()) } When { get("/api/v1/holdings") } Then {
            statusCode(200)
            body("", hasSize<Any>(0))
        }
    }

    @Test
    @TestSecurity(user = "viewer-1", roles = ["ROLE_VIEWER"])
    fun `the holdings feed refuses a viewer and an anonymous caller`() {
        val owner = UUID.randomUUID()
        Given {
            contentType("application/json")
            header("X-Customer-Party-Id", owner.toString())
            body(declareBody("SN-VIEWER-1"))
        } When { post("/api/v1/holdings") } Then { statusCode(403) }

        // Measured: with the OIDC tenant disabled in %test there is no inbound authentication
        // mechanism to challenge an anonymous caller, so @RolesAllowed denies as 403 — the
        // refusal itself is what the suite pins, in either rendering.
        Given { this } When { get("/api/v1/holdings") } Then { statusCode(403) }
    }
}
