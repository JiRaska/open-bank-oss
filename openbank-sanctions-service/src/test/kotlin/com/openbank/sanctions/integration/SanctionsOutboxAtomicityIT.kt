// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.integration

import com.openbank.sanctions.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `SanctionsRepositoryImpl` commits the `sanctions_checks` row and its
 * `sanctions_outbox` row in **one** database transaction, on BOTH of this service's write paths:
 * `saveWithEvent` (an automated screening, `POST /api/v1/sanctions/screen`) and `updateWithEvent`
 * (an analyst's manual disposition, `POST /api/v1/sanctions/review`).
 *
 * ### Why presence is not the property
 *
 * The house pattern drives the flow through the real REST endpoint and asserts the outbox row
 * landed. That is necessary — a mocked repository commits nothing, and a reactive Panache repo
 * cannot be called from a bare `@QuarkusTest` thread ("No current Vertx context found"), so only a
 * real HTTP request can exercise the write — but it is **not sufficient**: an implementation that
 * persisted the aggregate in one transaction and the outbox row in a second would satisfy every
 * presence assertion while having lost the property outright. The sibling
 * [com.openbank.sanctions.it.SanctionsIdempotentReplayIT] is in exactly that position — it counts
 * `sanctions_outbox` rows, and a two-transaction `saveWithEvent` would keep its counts at 1.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Comparing the two `xmin`s is a direct read of the property rather than of its happy-path shadow —
 * splitting either `Panache.withTransaction` in two turns this test red, where a presence assertion
 * stays green.
 *
 * `SanctionsRepositoryImpl` has asserted this in prose since #3264 ("Persist the check and its
 * outbox event in one transaction"); until now nothing measured it.
 *
 * ### Two async writers pinned
 *
 * `xmin` belongs to a row *version*, so any later UPDATE replaces it. The scheduled outbox
 * dispatcher claims rows and marks them DISPATCHING/SENT, so it is switched **off** for this class
 * — this service correctly ships `openbank.outbox.dispatch-enabled: true` in `application.yaml`
 * (the fleet default is `false`, under which a service dispatches nothing and reports no error),
 * which is precisely why it has to be disabled here. The list-refresh `@Scheduled` tick is already
 * off under `%test`.
 */
@QuarkusTest
@QuarkusTestResource(SanctionsOutboxAtomicityIT.NoOutboxDispatchResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsOutboxAtomicityIT {

    class NoOutboxDispatchResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.kafka.devservices.enabled" to "false",
        )

        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `screening commits the check row and its outbox row in one transaction`() {
        val checkId = screen("atomicity-it-${UUID.randomUUID()}")

        val rows = writersOf(checkId)

        assertThat(rows)
            .describedAs("exactly one sanctions_outbox row for check %s", checkId)
            .hasSize(1)
        val (checkXmin, outboxXmin, eventType) = rows.single()
        assertThat(eventType).isEqualTo(EVENT_SCREENED)
        assertThat(outboxXmin)
            .describedAs(
                "the sanctions_checks row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (check xmin=%s, outbox xmin=%s)",
                checkXmin,
                outboxXmin,
            )
            .isEqualTo(checkXmin)
    }

    /**
     * The review path, plus the **known-different** control this flow makes available for free:
     * the review MERGEs the check, so the row gets a new version with a new `xmin`, and the
     * screening outbox row — genuinely written by an earlier transaction — must NOT match it. One
     * run therefore shows the same comparison both matching and not matching, which is what makes
     * a match evidence rather than a coincidence of this database.
     */
    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `a manual review commits the updated check row with its own outbox row, not with the screening one`() {
        val checkId = screen("atomicity-it-${UUID.randomUUID()}")
        assertThat(review(checkId).statusCode).isEqualTo(200)

        val rows = writersOf(checkId)

        assertThat(rows)
            .describedAs("the screening row and the review row, in write order, for check %s", checkId)
            .hasSize(2)
        val screened = rows.first { it.eventType == EVENT_SCREENED }
        val reviewed = rows.first { it.eventType == EVENT_REVIEWED }

        assertThat(reviewed.outboxXmin)
            .describedAs(
                "the merged sanctions_checks row and the review's outbox row must carry the SAME " +
                    "xmin (check xmin=%s, review outbox xmin=%s)",
                reviewed.checkXmin,
                reviewed.outboxXmin,
            )
            .isEqualTo(reviewed.checkXmin)
        assertThat(screened.outboxXmin)
            .describedAs(
                "control: the SCREENING outbox row was written by an earlier transaction, so it " +
                    "must NOT match the reviewed row version — an assertion that matched here " +
                    "would be matching everything (check xmin=%s, screening outbox xmin=%s)",
                screened.checkXmin,
                screened.outboxXmin,
            )
            .isNotEqualTo(screened.checkXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a check id
     * that was never written must produce no pair at all, so `hasSize(1)` / `hasSize(2)` are claims
     * the query is capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a check that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val checkXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(checkId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT c.xmin::text AS check_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM sanctions_checks c
            JOIN sanctions_outbox o ON o.aggregate_id = c.id
            WHERE c.id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, checkId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    private fun screen(key: String): UUID {
        val created = RestAssured.given()
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "$key",
                  "entityType": "INDIVIDUAL",
                  "name": "Atomicity Subject",
                  "aliases": [],
                  "dateOfBirth": null,
                  "nationality": null,
                  "identifiers": {},
                  "listTypes": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/sanctions/screen")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/sanctions/screen: %s", created.body.asString())
            .isEqualTo(201)
        return UUID.fromString(created.jsonPath().getString("id"))
    }

    private fun review(checkId: UUID): Response = RestAssured.given()
        .contentType("application/json")
        .body(
            """
            {
              "checkId": "$checkId",
              "reviewedBy": "$ACTOR_ID",
              "note": "cleared by the atomicity IT",
              "newStatus": "CLEAR"
            }
            """.trimIndent(),
        )
        .post("/api/v1/sanctions/review")

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `SanctionsService.EVENT_SCREENED` / `EVENT_REVIEWED`, spelled out: the wire values are the subject. */
        const val EVENT_SCREENED = "SanctionChecked"
        const val EVENT_REVIEWED = "SanctionReviewed"
    }
}
