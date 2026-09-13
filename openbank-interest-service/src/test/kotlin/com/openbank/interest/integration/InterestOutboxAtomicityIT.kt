// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.interest.integration

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
 * Issue #8353 — proves that `InterestCapitalizationRepositoryImpl.saveWithOutbox` commits **all
 * four** of a capitalization's writes in one database transaction, so none of them can exist
 * without the others: the `interest_capitalizations` row, the paired `withholding_tax` liability,
 * the `interest_outbox` event, and the `CAPITALIZING -> CAPITALIZED` flip of every accrual the run
 * claimed.
 *
 * This is the money path itself. ADR-0033 credits net and records the withheld tax as a liability;
 * a crash that committed the capitalization without the accrual flip left the accruals claimable
 * next to a committed capitalization, and the retry re-credited the customer AND re-booked the tax.
 *
 * ### Why presence is not the property, and why the pair is not enough either
 *
 * The house pattern drives the flow through the real REST endpoint and asserts the outbox row
 * landed. That is necessary — a mocked repository commits nothing, and a reactive Hibernate repo
 * cannot be driven from a bare `@QuarkusTest` thread ("No current Vertx context found") — but not
 * sufficient: an implementation that wrote the aggregate in one transaction and the outbox row in a
 * second satisfies every presence assertion while having lost the property. The sibling
 * [InterestOutboxClaimIT] seeds outbox rows directly, so it is silent about the write.
 *
 * And a *pair* is not enough where a flow writes more than two rows: #8684 measured
 * `ClearingSettleOutboxAtomicityIT` staying green while the item writes were moved into their own
 * transaction, because it only ever compared the batch to its outbox row. Every one of the four
 * writes above is asserted here against the same transaction id, so moving any single one of them
 * out is red.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Rows
 * written by one transaction carry the *same* `xmin`; rows written by two cannot. Note the shape of
 * the sabotage this required: `saveWithOutbox` is annotated `@WithTransaction` **and** opens
 * `sf.withTransaction` inside it, so wrapping one write in a further nested `sf.withTransaction`
 * changes nothing at all — the interceptor's transaction is already current and the nested block
 * simply joins it. A falsification that changes nothing is indistinguishable from a test that
 * correctly resisted it. The transaction boundary has to be broken where it is actually created:
 * the `@WithTransaction` annotation removed and the write moved into a second, sequential
 * `sf.withTransaction`.
 *
 * ### The two switches this class does NOT need, and the one it does
 *
 * `interest`'s `%test` profile already sets `quarkus.scheduler.enabled: false`, so the scheduled
 * outbox dispatcher — whose claim UPDATE would give a row a new `xmin` and race the assertions —
 * never ticks here. The profile below pins `openbank.outbox.dispatch-enabled: false` regardless, so
 * this class does not silently depend on a `%test` key that belongs to a different concern. It is a
 * [QuarkusTestProfile] and not a `@QuarkusTestResource` deliberately: a test resource applies to
 * **every** class in the module, which is how the same change turned sdd's dispatch IT red in
 * #8676.
 *
 * `authz.enforce` needs no override, and that is worth stating rather than assuming: interest is
 * one of the seven services shipping `AUTHZ_ENFORCE` defaulted to **true** (#3679), so with no OPA
 * sidecar reachable the interceptor fails closed and every write endpoint would answer **503**, not
 * 403 — but its `%test` profile already opts back out to advisory, explicitly and with a comment
 * saying why. The 201/200 status assertions below are what actually establish that; a 503 would
 * fail them before any xmin was read.
 *
 * The ledger is the module-wide `@Mock` [LedgerBoundary], the same collaborator
 * [CapitalizationLedgerBoundaryIT] drives — `postCreditLeg` runs *outside* the transaction under
 * test (ADR-0033 §D, a network call must not hold it open), so it is arrangement here, not subject.
 */
@QuarkusTest
@TestProfile(InterestOutboxAtomicityIT.NoDispatchProfile::class)
@QuarkusTestResource(com.openbank.interest.it.PostgresRedisTestResource::class)
class InterestOutboxAtomicityIT {

    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            // The profile forces this class its own Quarkus boot, and interest's `%test` leaves
            // `quarkus.http.test-port` at the fixed default 8081. Billing's sibling class died on
            // exactly that restart (`QuarkusBindException: Port already bound: 8081`, reported as
            // one failure and two SKIPPED); port 0 is what swift's `%test` already does, and
            // Quarkus rewrites the property to the port it actually bound.
            "quarkus.http.test-port" to "0",
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `capitalization commits its capitalization, withholding, outbox and accrual rows in one transaction`() {
        val first = capitalizeOneAccount()

        assertThat(first.outboxXmin)
            .describedAs(
                "the interest_capitalizations row and its outbox row must carry the SAME Postgres " +
                    "xmin — different values mean two transactions wrote them, so one can commit " +
                    "without the other (capitalization xmin=%s, outbox xmin=%s)",
                first.capitalizationXmin,
                first.outboxXmin,
            )
            .isEqualTo(first.capitalizationXmin)

        assertThat(first.withholdingXmin)
            .describedAs(
                "the withholding_tax liability is the third row of the same write and must share " +
                    "that transaction (capitalization xmin=%s, withholding xmin=%s)",
                first.capitalizationXmin,
                first.withholdingXmin,
            )
            .isEqualTo(first.capitalizationXmin)

        // The fourth write, and the one a pair-only oracle is blind to (#8684): the accrual flip is
        // an UPDATE, so the row version now visible was written by whichever transaction flipped it.
        // Splitting the flip out leaves the capitalization committed over still-claimable accruals —
        // the exact double-credit this transaction exists to prevent.
        assertThat(first.accrualXmins)
            .describedAs("every claimed accrual was flipped by the capitalizing transaction")
            .isNotEmpty()
        assertThat(first.accrualXmins.distinct())
            .describedAs(
                "the CAPITALIZING -> CAPITALIZED flip must share the capitalizing transaction " +
                    "(capitalization xmin=%s, accrual xmins=%s)",
                first.capitalizationXmin,
                first.accrualXmins,
            )
            .containsExactly(first.capitalizationXmin)

        // Known-different control, so one run shows the identical comparison both matching and NOT
        // matching: a second account capitalized by a second transaction must not match the first.
        // Were the comparison matching everything, this would fail.
        val second = capitalizeOneAccount()
        assertThat(second.capitalizationXmin)
            .describedAs(
                "control: two capitalizations are two transactions (first xmin=%s, second xmin=%s)",
                first.capitalizationXmin,
                second.capitalizationXmin,
            )
            .isNotEqualTo(first.capitalizationXmin)
        assertThat(second.outboxXmin).isEqualTo(second.capitalizationXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a
     * capitalization id that was never written must produce no row at all, so every `isEqualTo`
     * above is a claim the query is capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a capitalization that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isNull()
    }

    private data class WriterRows(
        val capitalizationXmin: String,
        val outboxXmin: String,
        val withholdingXmin: String,
        val accrualXmins: List<String>,
    )

    /** Drives rate config -> accrual -> capitalization through the real REST API and reads the row set. */
    private fun capitalizeOneAccount(): WriterRows {
        val accountId = UUID.randomUUID()
        val productId = "SAVINGS_CZK_${UUID.randomUUID().toString().take(PRODUCT_SUFFIX_LENGTH)}"
        createRateConfig(productId)
        accrue(accountId, productId)
        val capitalizationId = capitalize(accountId, productId)
        return checkNotNull(writersOf(capitalizationId)) {
            "no capitalization row for $capitalizationId — the arrangement did not reach the write"
        }
    }

    /** The transaction ids (`xmin`) that wrote each of the four rows of one capitalization. */
    private fun writersOf(capitalizationId: UUID): WriterRows? {
        val head = rows(HEAD_SQL, capitalizationId) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
            .singleOrNull() ?: return null
        val accrualXmins = rows(ACCRUAL_SQL, capitalizationId) { it.getString(1) }
        return WriterRows(head.first, head.second, head.third, accrualXmins)
    }

    private fun <T> rows(sql: String, capitalizationId: UUID, map: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, capitalizationId)
                statement.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs else null }.map(map).toList()
                }
            }
        }

    private fun createRateConfig(productId: String) {
        Given {
            contentType("application/json")
            body(
                """
                {
                  "productId": "$productId",
                  "currency": "CZK",
                  "annualRate": 0.365000,
                  "effectiveFrom": "2026-01-01",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/interest/rates")
        } Then {
            statusCode(201)
        }
    }

    private fun accrue(accountId: UUID, productId: String) {
        val accrued = Given {
            contentType("application/json")
            body(
                """
                {
                  "accountId": "$accountId",
                  "productId": "$productId",
                  "balance": 100000.00,
                  "currency": "CZK",
                  "accrualDate": "2026-01-18"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/interest/accrue")
        } Then {
            statusCode(201)
        }
        // Arrangement assertion. A 422 (no rate config for this product/currency) also deserialises
        // to a body, and without this the capitalization below would find nothing to capitalize and
        // the test would assert over an empty row set while looking like it had passed.
        assertThat(accrued.extract().path<String>("status")).isEqualTo("ACCRUING")
    }

    private fun capitalize(accountId: UUID, productId: String): UUID {
        val capitalized = Given {
            contentType("application/json")
        } When {
            post("/api/v1/interest/capitalize/$accountId?productId=$productId&toDate=$PERIOD_TO")
        } Then {
            statusCode(200)
        }
        val body = capitalized.extract()
        // 0.365 annual / ACT_365 on a 100 000.00 balance is a 100.000000 accrual, so the gross is
        // strictly positive: a zero or negative gross takes a different branch that books no journal
        // (and, for negative, refuses before the claim), which would silently move the subject.
        assertThat(body.path<Float>("grossAmount").toDouble()).isGreaterThan(0.0)
        return UUID.fromString(body.path("id"))
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `interest_rate_configs.product_id` is bounded; a short unique suffix keeps runs independent. */
        const val PRODUCT_SUFFIX_LENGTH = 8

        const val PERIOD_TO = "2026-01-20"

        val HEAD_SQL = """
            SELECT c.xmin::text AS cap_xmin, o.xmin::text AS outbox_xmin, w.xmin::text AS wht_xmin
            FROM interest_capitalizations c
            JOIN interest_outbox o ON o.aggregate_id = c.id
            JOIN withholding_tax w ON w.capitalization_id = c.id
            WHERE c.id = ?
        """.trimIndent()

        val ACCRUAL_SQL = """
            SELECT a.xmin::text
            FROM interest_accruals a
            JOIN interest_capitalizations c
              ON c.account_id = a.account_id AND c.product_id = a.product_id
            WHERE c.id = ? AND a.status = 'CAPITALIZED'
            ORDER BY a.id
        """.trimIndent()
    }
}
