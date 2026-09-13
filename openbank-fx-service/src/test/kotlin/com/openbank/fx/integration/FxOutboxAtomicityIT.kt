// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.integration

import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningResult
import com.openbank.fx.domain.screening.ScreeningRole
import com.openbank.fx.infrastructure.client.SanctionsScreeningAdapter
import io.mockk.coEvery
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `FxConversionRepositoryImpl.saveWithOutbox` commits the
 * `fx_conversions` row and its `fx_outbox` row in **one** database transaction, so neither can
 * exist without the other.
 *
 * The subject is the SETTLE path (`FxService.settle`, reached by `POST /api/v1/fx/convert` once
 * screening returns CLEAR) — the one write in this service that moves a customer's money and then
 * announces it. The sibling HOLD path deliberately writes no outbox row at all: a conversion held
 * for AML review has nothing to announce yet.
 *
 * ### Why presence is not the property
 *
 * The house pattern drives the flow through the real REST endpoint and asserts the outbox row
 * landed. That is necessary — a mocked repository commits nothing, and a reactive Hibernate
 * repo cannot be driven from a bare `@QuarkusTest` thread ("No current Vertx context found"), so
 * only a real HTTP request can exercise the write — but it is **not sufficient**: an
 * implementation that persisted the aggregate in one transaction and the outbox row in a second
 * would satisfy every presence assertion while having lost the property. The sibling
 * `FxOutboxClaimIT` seeds outbox rows directly and tests claim/dispatch semantics, so it is silent
 * about the write.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Splitting `saveWithOutbox`'s single `sf.withTransaction` in two turns this test red, where a
 * presence assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`, which would race the assertion. (This
 * service correctly ships `openbank.outbox.dispatch-enabled: true` in `application.yaml` — the
 * fleet default is `false`, under which a service dispatches nothing and reports no error — which
 * is precisely why it has to be disabled here.)
 *
 * Sanctions screening is the one collaborator that decides which write path runs, so it is mocked
 * to CLEAR rather than left to fail: an unreachable sanctions service raises
 * `ScreeningUnavailableException` and the use case fails closed into HOLD, which writes no outbox
 * row — the test would then assert nothing while looking like it had. Fraud scoring, by contrast,
 * fails **open** by design (#4221) and runs after the write, so it needs no stub.
 */
@QuarkusTest
@QuarkusTestResource(FxOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.fx.it.PostgresRedisTestResource::class)
class FxOutboxAtomicityIT {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("fx-events-out") +
                mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun screeningIsClear() {
        val port = mockk<SanctionsScreeningAdapter>()
        coEvery { port.screen(any(), any(), any()) } answers {
            ScreeningResult(
                subject = firstArg(),
                role = ScreeningRole.DEBTOR,
                status = ScreeningMatchStatus.CLEAR,
                score = 0.0,
                matchedEntity = null,
            )
        }
        QuarkusMock.installMockForType(port, SanctionsScreeningAdapter::class.java)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_PAYMENTS"])
    fun `a settled conversion commits the conversion row and its outbox row in one transaction`() {
        val first = convert()
        val second = convert()

        val firstRows = writersOf(first)
        val secondRows = writersOf(second)

        assertThat(firstRows)
            .describedAs("exactly one fx_outbox row for conversion %s", first)
            .hasSize(1)
        assertThat(secondRows).hasSize(1)

        val (conversionXmin, outboxXmin, eventType) = firstRows.single()
        assertThat(eventType).isEqualTo(EVENT_CONVERSION_EXECUTED)
        assertThat(outboxXmin)
            .describedAs(
                "the fx_conversions row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (conversion xmin=%s, outbox xmin=%s)",
                conversionXmin,
                outboxXmin,
            )
            .isEqualTo(conversionXmin)
        assertThat(secondRows.single().outboxXmin).isEqualTo(secondRows.single().conversionXmin)

        // Known-different control: the two conversions were written by two different requests, so
        // two different transactions. The identical comparison must therefore FAIL across them —
        // otherwise the matches above would be matching everything and could not fail.
        assertThat(secondRows.single().outboxXmin)
            .describedAs(
                "control: two separate conversions cannot share a writing transaction (first=%s, second=%s)",
                outboxXmin,
                secondRows.single().outboxXmin,
            )
            .isNotEqualTo(outboxXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a
     * conversion id that was never written must produce no pair at all, so `hasSize(1)` is a claim
     * the query is capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a conversion that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val conversionXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(conversionId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT c.xmin::text AS conversion_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM fx_conversions c
            JOIN fx_outbox o ON o.aggregate_id = c.id
            WHERE c.id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversionId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    private fun convert(): UUID {
        val created = RestAssured.given()
            .contentType("application/json")
            .header("Idempotency-Key", "atomicity-it-${UUID.randomUUID()}")
            .body(
                """
                {
                  "partyId": "${UUID.randomUUID()}",
                  "accountId": null,
                  "partyName": "Jan Novak",
                  "fromCurrency": "CZK",
                  "toCurrency": "EUR",
                  "fromAmountMinorUnits": 100000
                }
                """.trimIndent(),
            )
            .post("/api/v1/fx/convert")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/fx/convert: %s", created.body.asString())
            .isEqualTo(201)
        // Arrangement assertion: only a SETTLED conversion reaches saveWithOutbox. A change that
        // routed this request to the HOLD path would otherwise leave the test silently asserting
        // over an empty outbox.
        assertThat(created.jsonPath().getString("status")).isEqualTo("SETTLED")
        return UUID.fromString(created.jsonPath().getString("id"))
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `FxService.EVENT_FX_CONVERSION_EXECUTED` — the wire value is the subject. */
        const val EVENT_CONVERSION_EXECUTED = "fx.conversion.executed.v1"
    }
}
