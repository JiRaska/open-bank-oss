// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.integration

import com.openbank.cardissuance.application.usecase.CardService
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Issue #8353 — proves that `CardRepositoryImpl.save` commits the `cards` row and its `card_outbox`
 * row in **one** database transaction, on both of its branches: the INSERT that issues a card and
 * the in-place UPDATE that changes its status.
 *
 * ### Why presence is not the property
 *
 * The sibling outbox ITs here seed `card_outbox` rows directly and test claim, dispatch and requeue;
 * they are silent about the write. And asserting that an outbox row exists after an issue would not
 * be enough either: an implementation that persisted the card in one transaction and the event in a
 * second satisfies that while having lost the property — a crash between them leaves a card nobody
 * downstream hears about.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Rows
 * written by one transaction share it; rows written by two cannot. The UPDATE branch is what makes
 * this sharper than the insert alone: blocking a card rewrites the `cards` row, so its `xmin` moves
 * to the blocking transaction and must now equal the `card.status_changed.v1` row's — and no longer
 * the `card.issued.v1` row's, which is the known-different control.
 *
 * The scheduled dispatcher is switched off for this class: its claim UPDATE would restamp `xmin` on
 * the outbox rows and race the assertions.
 * Both writes run through the served REST routes with a test operator identity. This exercises the
 * HTTP/CDI/Vert.x context that a direct call from a bare test thread cannot establish.
 */
@QuarkusTest
@QuarkusTestResource(CardOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.cardissuance.it.PostgresRedisTestResource::class)
@TestSecurity(user = "atomicity-operator", roles = ["ROLE_OPERATOR"])
class CardOutboxAtomicityIT {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("card-events-out") +
                mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `issuing a card writes the card row and its outbox row in one transaction`() {
        val first = issue()
        val second = issue()

        val firstCard = cardXmin(first)
        val firstIssued = outboxXmin(first, CardService.EVENT_CARD_ISSUED)
        assertThat(firstCard).describedAs("card %s must exist", first).hasSize(1)
        assertThat(firstIssued).describedAs("exactly one card.issued.v1 row").hasSize(1)

        assertThat(firstIssued.single())
            .describedAs(
                "the cards row and its outbox row must carry the SAME Postgres xmin — different " +
                    "values mean two transactions wrote them, so one can commit without the other",
            )
            .isEqualTo(firstCard.single())

        // Known-different control: two issues are two transactions, so the identical comparison
        // must FAIL across them — otherwise the match above would match everything.
        assertThat(cardXmin(second).single())
            .describedAs("control: two separate issues cannot share a writing transaction")
            .isNotEqualTo(firstCard.single())
    }

    @Test
    fun `a status change rewrites the card row and writes its outbox row in one transaction`() {
        val cardId = issue()
        val issuedXmin = outboxXmin(cardId, CardService.EVENT_CARD_ISSUED).single()

        given()
            .contentType(ContentType.JSON)
            .header("X-Operator-Id", "atomicity-operator")
            .body("""{"reason":"atomicity IT"}""")
            .`when`().post("/api/v1/cards/$cardId/block")
            .then().statusCode(200).body("status", equalTo("BLOCKED"))
        // Arrangement assertion: only a transition that actually happened writes anything.

        val cardAfter = cardXmin(cardId).single()
        val changed = outboxXmin(cardId, CardService.EVENT_CARD_STATUS_CHANGED)
        assertThat(changed).describedAs("exactly one card.status_changed.v1 row").hasSize(1)
        assertThat(changed.single())
            .describedAs("the UPDATE of the cards row and the status-change event share one transaction")
            .isEqualTo(cardAfter)
        assertThat(cardAfter)
            .describedAs("control: the rewritten cards row no longer carries the issuing transaction's xmin")
            .isNotEqualTo(issuedXmin)
    }

    /** Guards the `hasSize(1)` claims above against a query that cannot return anything. */
    @Test
    fun `the probe returns nothing for a card that was never written`() {
        assertThat(cardXmin(UUID.randomUUID())).isEmpty()
        assertThat(outboxXmin(UUID.randomUUID(), CardService.EVENT_CARD_ISSUED)).isEmpty()
    }

    private fun issue(): UUID {
        val partyId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val response = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "atomicity-it-${UUID.randomUUID()}")
            .body(
                """{"partyId":"$partyId","accountId":"$accountId","productCode":"ATOMICITY-IT","cardType":"VIRTUAL","network":"VISA","cardholderName":"Jan Novak","embossedName":"JAN NOVAK","currency":"CZK","dailyLimitMinorUnits":100000,"monthlyLimitMinorUnits":1000000}""",
            )
            .`when`().post("/api/v1/cards")
            .then().statusCode(201).body("status", equalTo("ACTIVE"))
            .extract().response()
        return UUID.fromString(response.jsonPath().getString("id"))
    }

    private fun cardXmin(cardId: UUID): List<String> = jdbc { connection ->
        connection.prepareStatement("select xmin::text from cards where id = ?").use { statement ->
            statement.setObject(1, cardId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }
    }

    private fun outboxXmin(cardId: UUID, eventType: String): List<String> = jdbc { connection ->
        connection.prepareStatement(
            "select xmin::text from card_outbox where aggregate_id = ? and event_type = ? order by id",
        ).use { statement ->
            statement.setObject(1, cardId)
            statement.setString(2, eventType)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }
}
