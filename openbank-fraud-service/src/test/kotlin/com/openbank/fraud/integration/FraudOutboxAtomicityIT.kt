// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `FraudHoldService.maybeRaise` commits the `fraud_hold` row and its
 * `fraud_outbox` row in **one** database transaction, so neither can exist without the other.
 *
 * The hold is the service's only durable write besides the immutable score audit row, and the
 * `fraud.hold_changed` event is how every downstream consumer learns a party is held — a hold that
 * committed without its event is a party restricted with nothing told, and an event without its
 * hold is the reverse.
 *
 * ### Why presence is not the property
 *
 * The house pattern (`LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`) drives the flow through
 * the real REST endpoint and asserts the outbox row landed. That is necessary — a mocked repository
 * commits nothing, and a reactive Panache repo cannot be called from a bare `@QuarkusTest` thread
 * ("No current Vertx context found") — but it is **not sufficient**: an implementation that
 * persisted the aggregate in one transaction and the outbox row in a second would satisfy every
 * presence assertion while having lost the property. The sibling [FraudHoldServiceIT] is in exactly
 * that position: it asserts `outboxCountFor("fraud.hold_changed") >= 1`, which a two-transaction
 * write answers identically. It also calls `holdService.maybeRaise` directly rather than through
 * HTTP, so it cannot see a defect that only the real request path reaches.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Splitting `maybeRaise`'s single `Panache.withTransaction` in two turns this test red, where a
 * presence assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`, which would race the assertion. (This
 * service correctly ships `openbank.outbox.dispatch-enabled: true` in `application.yaml` — the
 * fleet default is `false`, under which a service dispatches nothing and reports no error — which
 * is precisely why it has to be disabled here rather than merely left alone.) That switch lives in
 * a [QuarkusTestProfile] and NOT in a test resource, deliberately: a `@QuarkusTestResource` is
 * applied to every test class in the module, so a resource carrying `dispatch-enabled=false` would
 * silently disable dispatch for the module's other ITs too — measured in the sdd half of this
 * change, where doing so turned that module's dispatch IT red. A profile is per-class and forces
 * this class its own Quarkus boot, which is exactly the scope wanted.
 *
 * `AccountPartyLookupPort` is the one collaborator that decides whether the write path runs at all:
 * `maybeRaise` returns early when the account maps to no party, so with no account-service
 * reachable the test would assert nothing while looking like it had. [StubAccountPartyLookupPort]
 * (`@Alternative @Priority(1)`, declared alongside [FraudHoldServiceIT]) already replaces it for
 * this module's tests; this class only sets which party it answers with.
 */
@QuarkusTest
@TestProfile(FraudOutboxAtomicityIT.NoDispatchProfile::class)
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FraudOutboxAtomicityIT {

    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_API"])
    fun `raising a hold commits the hold row and its outbox row in one transaction`() {
        val firstParty = UUID.randomUUID()
        val firstAccount = raiseHoldFor(firstParty)

        val rows = writersOf(firstParty)
        assertThat(rows)
            .describedAs("exactly one fraud_outbox row for party %s (account %s)", firstParty, firstAccount)
            .hasSize(1)
        val (holdXmin, outboxXmin, eventType) = rows.single()
        assertThat(eventType).isEqualTo(EVENT_HOLD_CHANGED)
        assertThat(outboxXmin)
            .describedAs(
                "the fraud_hold row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (hold xmin=%s, outbox xmin=%s)",
                holdXmin,
                outboxXmin,
            )
            .isEqualTo(holdXmin)

        // Known-different control, so one run shows the identical comparison both matching and NOT
        // matching. A hold for a second party is a second request and therefore a second
        // transaction; if its outbox row shared a writer with the first, the match above would be
        // matching everything and could not fail.
        val secondParty = UUID.randomUUID()
        raiseHoldFor(secondParty)
        val secondRows = writersOf(secondParty)
        assertThat(secondRows).hasSize(1)
        assertThat(secondRows.single().outboxXmin).isEqualTo(secondRows.single().holdXmin)
        assertThat(secondRows.single().outboxXmin)
            .describedAs(
                "control: two separate holds cannot share a writing transaction (first=%s, second=%s)",
                outboxXmin,
                secondRows.single().outboxXmin,
            )
            .isNotEqualTo(outboxXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a party id
     * that was never held must produce no pair at all, so `hasSize(1)` is a claim the query is
     * capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a party that was never held`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val holdXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(partyId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT h.xmin::text AS hold_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM fraud_hold h
            JOIN fraud_outbox o ON o.aggregate_id = h.party_id
            WHERE h.party_id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, partyId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    /**
     * Drives `POST /api/v1/fraud/score` until the repeated-REVIEW rule fires. A CZK amount over
     * `LargeSingleTransactionReviewRule`'s 500 000 threshold makes every call a REVIEW, and
     * `openbank.fraud-hold.review-threshold` is 3 — so the third request is the one whose
     * `maybeRaise` writes the hold. `score` awaits `maybeRaise`, so the write has committed by the
     * time the third response returns; nothing here polls.
     */
    private fun raiseHoldFor(partyId: UUID): UUID {
        val accountId = UUID.randomUUID()
        StubAccountPartyLookupPort.partyId = partyId
        repeat(REVIEW_THRESHOLD) {
            val scored = Given {
                contentType("application/json")
                body(
                    """
                    {
                      "amount": "600000.00",
                      "currency": "CZK",
                      "rail": "SEPA",
                      "accountId": "$accountId",
                      "counterpartyId": "${UUID.randomUUID()}"
                    }
                    """.trimIndent(),
                )
            } When {
                post("/api/v1/fraud/score")
            } Then {
                statusCode(200)
            }
            // Arrangement assertion: only a REVIEW verdict counts towards the hold threshold. A
            // rule-engine change that made this amount ALLOW would otherwise leave the test
            // silently asserting over an empty outbox.
            assertThat(scored.extract().path<String>("verdict")).isEqualTo("REVIEW")
        }
        return accountId
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `openbank.fraud-hold.review-threshold` in `application.yaml`. */
        const val REVIEW_THRESHOLD = 3

        /** `FraudHoldService.emitHoldChanged` — the wire value is the subject. */
        const val EVENT_HOLD_CHANGED = "fraud.hold_changed"
    }
}
