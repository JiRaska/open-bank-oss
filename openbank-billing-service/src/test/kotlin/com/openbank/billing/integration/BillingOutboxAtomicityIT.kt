// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.ProductCatalogPort
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.libs.product.FeeContext
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that both of billing's outbox write paths commit their rows in **one**
 * database transaction, so none of them can exist without the others:
 *
 *  1. `persistWithPostingIntent` (`POST /api/v1/fees/post`) — the `billing_cycle_assessment` row,
 *     one `assessed_fee` row per fee, and one `billing_outbox` `fee.post-intent` row per
 *     *chargeable* fee, and
 *  2. `persistReversalIntent` (`POST /api/v1/fees/reverse`) — the `POSTED -> REVERSAL_PENDING`
 *     flip of the fee together with its compensating-journal `fee.reversal-intent` row.
 *
 * Both are money-bearing: the outbox row IS the instruction to move money in the general ledger
 * (`LedgerOutboxEventPublisher`), so an assessment that committed without its intent charges
 * nothing while the customer's statement says it did, and a reversal flip that committed without
 * its intent leaves a fee marked REVERSAL_PENDING forever with no compensating journal ever posted.
 *
 * ### Why presence is not the property, and why one pair is not enough
 *
 * [BillingCycleServiceIT] and [FeeReversalServiceIT] already assert the outbox *backlog count*
 * moved. That is necessary but not sufficient: an implementation that wrote the assessment in one
 * transaction and the outbox row in a second satisfies every count assertion while having lost the
 * property. And #8684 measured the sharper failure — an oracle that compares only the aggregate to
 * its outbox row stays green when a *third* row is moved into its own transaction. Path 1 writes
 * three kinds of row, so all three are pinned to the same transaction id here.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Rows
 * written by one transaction carry the *same* `xmin`; rows written by two cannot. Both write paths
 * create their transaction with a bare `sf.withTransaction` and neither `BillingResource` nor the
 * services carry `@WithTransaction`, so that block is the real boundary and splitting it is a
 * genuine split — unlike an interceptor-annotated method, where a *nested* `withTransaction` merely
 * joins the ambient one and changes nothing.
 *
 * ### The switches, and why they live in a profile
 *
 * The scheduled outbox dispatcher UPDATEs claimed rows, and an UPDATE writes a new row version with
 * a new `xmin`; the ledger publisher then also flips the fee to POSTED. Both are off here.
 * `billing`'s `%test` already disables `quarkus.scheduler`, and the profile pins
 * `openbank.outbox.dispatch-enabled: false` as well so this class does not silently depend on a
 * `%test` key that belongs to a different concern.
 *
 * The two read-side stubs are enabled through [QuarkusTestProfile.getEnabledAlternatives] rather
 * than `@io.quarkus.test.Mock`, and that distinction is load-bearing: an `@Alternative` selected by
 * a profile is active for this class only, whereas a module-wide mock would replace
 * `RestAccountContextPort` for [BillingCycleServiceIT] too — whose whole subject is the fail-closed
 * `ACCOUNT_CONTEXT_UNRESOLVED` skip that an always-resolving stub destroys. #8676 measured the same
 * class of collateral damage from a module-wide `@QuarkusTestResource`.
 */
@QuarkusTest
@TestProfile(BillingOutboxAtomicityIT.ChargeableFeeNoDispatchProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class BillingOutboxAtomicityIT {

    class ChargeableFeeNoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            // A profile forces this class its own Quarkus boot, and billing's `%test` leaves
            // `quarkus.http.test-port` at the fixed default 8081. Measured: the restart re-binds
            // that port before the outgoing instance has released it and the boot dies with
            // `QuarkusBindException: Port already bound: 8081`, which reports as ONE failure and
            // TWO SKIPPED — a skip count that scans as a pass. Port 0 is what swift's `%test`
            // already does; Quarkus rewrites the property to the port it actually bound, so
            // RestAssured still finds it.
            "quarkus.http.test-port" to "0",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(ResolvingAccountContext::class.java, OneChargeableFeeCatalog::class.java)
    }

    /** account-service + balance-service stand-in: always resolves, so nothing is skipped. */
    @Alternative
    @ApplicationScoped
    class ResolvingAccountContext : AccountContextPort {
        override suspend fun resolve(accountId: String, currency: String) = AccountBilling(
            productId = PRODUCT_ID,
            context = FeeContext(balance = BigDecimal("10.00"), currency = currency),
        )
    }

    /**
     * One non-waivable fee, so exactly one `assessed_fee` row is chargeable and exactly one
     * post-intent outbox row is written. Waivable-with-condition fees would make the count depend
     * on the waiver evaluation rather than on the write under test.
     */
    @Alternative
    @ApplicationScoped
    class OneChargeableFeeCatalog : ProductCatalogPort {
        override suspend fun billableFees(productId: String, currency: String) = listOf(
            BillableFee(
                feeId = FEE_ID,
                name = "Monthly maintenance",
                type = "MAINTENANCE",
                amount = BigDecimal("150.00"),
                currency = currency,
                waivable = false,
                waiveCondition = null,
            ),
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var repository: BillingAssessmentRepository

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `assess-and-post commits the assessment, its fee row and the post-intent row in one transaction`() {
        val cycleId = "it-atomicity-${System.nanoTime()}"
        val accountId = "acc-atomicity-${UUID.randomUUID()}"
        assessAndPost(cycleId, accountId)

        val rows = postIntentRowsOf(cycleId, accountId)
        assertThat(rows)
            .describedAs("exactly one chargeable fee and one post-intent outbox row for %s", accountId)
            .hasSize(1)
        val row = rows.single()
        assertThat(row.eventType).isEqualTo(POST_INTENT)

        assertThat(row.outboxXmin)
            .describedAs(
                "the billing_cycle_assessment row and its post-intent outbox row must carry the " +
                    "SAME Postgres xmin — different values mean two transactions wrote them, so one " +
                    "can commit without the other, and that row IS the instruction to charge the " +
                    "customer in the ledger (assessment xmin=%s, outbox xmin=%s)",
                row.assessmentXmin,
                row.outboxXmin,
            )
            .isEqualTo(row.assessmentXmin)

        // The third row, and the one a pair-only oracle is blind to (#8684): moving just the
        // assessed_fee writes into their own transaction leaves the assertion above green while the
        // fee the outbox row instructs the ledger to charge may not exist at all.
        assertThat(row.feeXmin)
            .describedAs(
                "the assessed_fee row is the third row of the same write and must share that " +
                    "transaction (assessment xmin=%s, fee xmin=%s)",
                row.assessmentXmin,
                row.feeXmin,
            )
            .isEqualTo(row.assessmentXmin)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `reversing a posted fee commits the status flip and the reversal-intent row in one transaction`() {
        val cycleId = "it-reversal-${System.nanoTime()}"
        val accountId = "acc-reversal-${UUID.randomUUID()}"
        assessAndPost(cycleId, accountId)
        val idempotencyKey = "fee-$cycleId-$accountId-$FEE_ID-$CURRENCY"
        // Arrangement only: the fee must be POSTED before it can be reversed, and in production that
        // flip is the outbox dispatcher's ledger call, which is switched off here.
        onVertxContext { repository.markPosted(idempotencyKey, UUID.randomUUID()) }

        reverse(idempotencyKey)

        val rows = reversalRowsOf(idempotencyKey)
        assertThat(rows)
            .describedAs("exactly one reversal-intent outbox row for %s", idempotencyKey)
            .hasSize(1)
        val row = rows.single()
        assertThat(row.outboxXmin)
            .describedAs(
                "the POSTED -> REVERSAL_PENDING flip and its compensating-journal outbox row must " +
                    "carry the same Postgres xmin (fee xmin=%s, outbox xmin=%s)",
                row.feeXmin,
                row.outboxXmin,
            )
            .isEqualTo(row.feeXmin)

        // Known-different control, so one run shows the identical comparison both matching and NOT
        // matching. The reversal UPDATEd the fee row, giving it a new version whose xmin is the
        // reversing transaction's; the POST-INTENT row was written by the assessing transaction and
        // therefore must NOT match. Were the comparison matching everything, this would fail.
        val postIntent = postIntentRowsOf(cycleId, accountId).single()
        assertThat(postIntent.outboxXmin)
            .describedAs(
                "control: the post-intent row was written by an earlier transaction than the " +
                    "current fee row version (post-intent xmin=%s, fee xmin=%s)",
                postIntent.outboxXmin,
                postIntent.feeXmin,
            )
            .isNotEqualTo(postIntent.feeXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a cycle and
     * an idempotency key that were never written must produce no pair at all, so every `hasSize(1)`
     * is a claim the queries are capable of failing.
     */
    @Test
    fun `the atomicity queries return nothing for rows that were never written`() {
        assertThat(postIntentRowsOf("no-such-cycle", "no-such-account")).isEmpty()
        assertThat(reversalRowsOf("fee-no-such-key")).isEmpty()
    }

    private data class WriterRow(
        val assessmentXmin: String,
        val feeXmin: String,
        val outboxXmin: String,
        val eventType: String,
    )

    private fun postIntentRowsOf(cycleId: String, accountId: String): List<WriterRow> = query(POST_INTENT_SQL) {
        it.setString(1, cycleId)
        it.setString(2, accountId)
    }

    private fun reversalRowsOf(idempotencyKey: String): List<WriterRow> =
        query(REVERSAL_SQL) { it.setString(1, idempotencyKey) }

    private fun query(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<WriterRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows else null }
                        .map { WriterRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
                        .toList()
                }
            }
        }

    private fun assessAndPost(cycleId: String, accountId: String) {
        val posted = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }
        // Arrangement assertion: billing fails CLOSED, so an unresolvable account or an unreachable
        // catalog yields a `skipped` assessment with no fees and no outbox rows at all — a state in
        // which every assertion below would pass vacuously over an empty set.
        val body = posted.extract()
        assertThat(body.path<Boolean>("skipped"))
            .describedAs("the stubs really resolved, so the write path under test was reached")
            .isFalse()
        assertThat(body.path<List<Any>>("assessedFees")).hasSize(1)
    }

    private fun reverse(idempotencyKey: String) {
        val reversed = Given {
            contentType("application/json")
            body("""{"reason":"assessed under a superseded tariff"}""")
        } When {
            post("/api/v1/fees/reverse?idempotencyKey=$idempotencyKey")
        } Then {
            statusCode(200)
        }
        assertThat(reversed.extract().path<String>("postingStatus")).isEqualTo("REVERSAL_PENDING")
    }

    /** Panache reactive needs a Vert.x duplicated context; the JUnit thread is not one. */
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
        const val PRODUCT_ID = "CURRENT_CZK"
        const val FEE_ID = "maintenance-monthly"
        const val CURRENCY = "CZK"

        /** The wire values `BillingAssessmentRepositoryImpl` stamps on the two rows — the subject. */
        const val POST_INTENT = "billing.fee.post-intent.v1"
        const val REVERSAL_INTENT = "billing.fee.reversal-intent.v1"

        val POST_INTENT_SQL = """
            SELECT a.xmin::text, f.xmin::text, o.xmin::text, o.event_type
            FROM billing_cycle_assessment a
            JOIN assessed_fee f ON f.assessment_id = a.id
            JOIN billing_outbox o ON o.aggregate_id = f.id AND o.event_type = '$POST_INTENT'
            WHERE a.cycle_id = ? AND a.account_id = ?
            ORDER BY o.id
        """.trimIndent()

        val REVERSAL_SQL = """
            SELECT a.xmin::text, f.xmin::text, o.xmin::text, o.event_type
            FROM assessed_fee f
            JOIN billing_cycle_assessment a ON a.id = f.assessment_id
            JOIN billing_outbox o ON o.aggregate_id = f.id AND o.event_type = '$REVERSAL_INTENT'
            WHERE f.idempotency_key = ?
            ORDER BY o.id
        """.trimIndent()
    }
}
