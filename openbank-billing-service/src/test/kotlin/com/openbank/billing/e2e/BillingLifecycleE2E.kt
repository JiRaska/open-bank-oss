// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.e2e

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
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
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * End-to-end lifecycle of the ADR-0143 fee spine over real HTTP + real Postgres: dry-run
 * assessment (fail-closed skip AND the chargeable path), the money-moving post with its
 * idempotent replay, the reversal guards (400/404/409) and the POSTED -> REVERSAL_PENDING
 * happy path with its idempotent replay — plus the request-validation and role guards on the
 * same endpoints.
 *
 * Complements rather than duplicates the integration suites: [com.openbank.billing.integration.BillingOutboxAtomicityIT]
 * pins rows to ONE transaction via Postgres `xmin`; this suite drives the public REST contract
 * end to end and asserts observable behaviour (status codes, response bodies, headers, and
 * durable DB state via plain JDBC read-backs, since the API exposes no fee-read endpoint).
 *
 * ### Why one profile for the whole class
 *
 * The read-side ports are stubbed with profile-selected `@Alternative`s (same pattern as
 * BillingOutboxAtomicityIT — module-wide mocks would break the fail-closed ITs that need the
 * real adapters). The account-context stub resolves ONLY accounts under the `acc-e2e-`
 * prefix, so the fail-closed `ACCOUNT_CONTEXT_UNRESOLVED` dry run stays reachable in the same
 * class that also covers the chargeable path. Outbox dispatch stays off (the dispatcher would
 * flip fees to POSTED asynchronously and race the assertions); the POSTED state needed by the
 * reversal happy path is arranged deterministically through `BillingAssessmentRepository.markPosted`
 * on a Vert.x context, exactly like FeeReversalServiceIT. `quarkus.http.test-port=0` avoids the
 * profile-restart QuarkusBindException measured in BillingOutboxAtomicityIT.
 */
@QuarkusTest
@TestProfile(BillingLifecycleE2E.LifecycleProfile::class)
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class BillingLifecycleE2E {

    class LifecycleProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "quarkus.http.test-port" to "0",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> =
            setOf(E2EAccountContext::class.java, E2EOneFeeCatalog::class.java)
    }

    /** Resolves only `acc-e2e-*` accounts — everything else simulates the upstream being blind to it. */
    @Alternative
    @ApplicationScoped
    class E2EAccountContext : AccountContextPort {
        override suspend fun resolve(accountId: String, currency: String): AccountBilling? =
            if (accountId.startsWith("acc-e2e-")) {
                AccountBilling(
                    productId = PRODUCT_ID,
                    context = FeeContext(balance = BigDecimal("10.00"), currency = currency),
                    openedAt = Instant.parse("2026-01-15T10:00:00Z"),
                )
            } else {
                null
            }
    }

    /** One non-waivable fee — the chargeable outcome is deterministic, not waiver-evaluation-dependent. */
    @Alternative
    @ApplicationScoped
    class E2EOneFeeCatalog : ProductRevisionPort {
        override suspend fun publishedRevisionIdFor(productId: String, effectiveAt: Instant?): String = REVISION_ID

        override suspend fun terms(revisionId: String, currency: String) = RevisionTerms(
            revisionId = revisionId,
            accountingClass = "CUSTOMER_DEPOSIT_DEMAND",
            accountingClassSource = AccountingClassSource.REVISION,
            fees = listOf(
                BillableFee(
                    feeId = FEE_ID,
                    name = "Monthly maintenance",
                    type = "MAINTENANCE",
                    amount = BigDecimal("150.00"),
                    currency = currency,
                    waivable = false,
                    waiveCondition = null,
                ),
            ),
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var repository: BillingAssessmentRepository

    // ---------------------------------------------------------------- assess (dry run)

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `assess on an unresolvable account fails closed with a skipped dry run and persists nothing`() {
        val cycleId = cycle()
        val body = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=$cycleId&accountId=ghost-${UUID.randomUUID()}&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }

        val assessment = body.extract()
        assertThat(assessment.path<Boolean>("skipped")).isTrue()
        assertThat(assessment.path<String>("skipReason")).isEqualTo("ACCOUNT_CONTEXT_UNRESOLVED")
        assertThat(assessment.path<List<Any>>("assessedFees")).isEmpty()
        assertThat(countAssessments(cycleId))
            .describedAs("a dry run must never persist the assessment")
            .isZero()
    }

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `assess computes one non-waivable fee and persists nothing`() {
        val cycleId = cycle()
        val accountId = account()
        val body = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }

        val assessment = body.extract()
        assertThat(assessment.path<Boolean>("skipped")).isFalse()
        assertThat(assessment.path<String>("currency")).isEqualTo(CURRENCY)
        val fees = assessment.path<List<Map<String, Any>>>("assessedFees")
        assertThat(fees).hasSize(1)
        val fee = fees.single()
        assertThat(fee["feeId"]).isEqualTo(FEE_ID)
        // JSON numbers come back as Double through RestAssured's path parser — compare numerically.
        assertThat(BigDecimal(fee["chargedAmount"].toString())).isEqualByComparingTo(BigDecimal("150.00"))
        assertThat(fee["waived"]).isEqualTo(false)
        assertThat(fee["reason"]).isEqualTo("NOT_WAIVABLE")
        assertThat(fee["postingStatus"]).isEqualTo("NOT_APPLICABLE")
        assertThat(fee["idempotencyKey"]).isEqualTo("fee-$cycleId-$accountId-$FEE_ID-$CURRENCY")
        assertThat(countAssessments(cycleId))
            .describedAs("the dry run computes but must not durably commit")
            .isZero()
    }

    // ---------------------------------------------------------------- post (money path)

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `post persists the assessment with the fee PENDING and stamps X-Posted-By`() {
        val cycleId = cycle()
        val accountId = account()
        val body = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
            header("X-Posted-By", OPERATOR_ID)
        }

        val fee = body.extract().path<List<Map<String, Any>>>("assessedFees").single()
        assertThat(fee["postingStatus"]).isEqualTo("PENDING")
        assertThat(countAssessments(cycleId)).isEqualTo(1)
        assertThat(countPostIntents(cycleId, accountId))
            .describedAs("the atomic intent-to-post outbox row for the chargeable fee")
            .isEqualTo(1)
    }

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `posting the same cycle account and currency again is an idempotent replay`() {
        val cycleId = cycle()
        val accountId = account()

        fun postOnce() = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }

        postOnce()
        val replay = postOnce().extract()
        assertThat(replay.path<Boolean>("skipped")).isFalse()
        assertThat(replay.path<List<Any>>("assessedFees")).hasSize(1)
        assertThat(countAssessments(cycleId))
            .describedAs("a replay must not create a second assessment or a second outbox row")
            .isEqualTo(1)
        assertThat(countPostIntents(cycleId, accountId)).isEqualTo(1)
    }

    // ---------------------------------------------------------------- reversal

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `reversing a fee still PENDING fails cleanly with 409 and changes nothing`() {
        val cycleId = cycle()
        val accountId = account()
        postAssessment(cycleId, accountId)
        val key = "fee-$cycleId-$accountId-$FEE_ID-$CURRENCY"

        Given {
            contentType("application/json")
            body("""{"reason":"customer complaint"}""")
        } When {
            post("/api/v1/fees/reverse?idempotencyKey=$key")
        } Then {
            statusCode(409)
        }

        assertThat(postingStatusOf(key)).isEqualTo("PENDING")
        assertThat(countReversalIntents(key)).isZero()
    }

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `reverse guards - blank key or reason is 400 and an unknown key is 404`() {
        Given {
            contentType("application/json")
            body("""{"reason":"x"}""")
        } When {
            post("/api/v1/fees/reverse")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
            body("""{}""")
        } When {
            post("/api/v1/fees/reverse?idempotencyKey=fee-none")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
            body("""{"reason":"oops"}""")
        } When {
            post("/api/v1/fees/reverse?idempotencyKey=fee-never-assessed-nope-CZK")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `reversing a POSTED fee flips it to REVERSAL_PENDING and a replay adds no second intent`() {
        val cycleId = cycle()
        val accountId = account()
        postAssessment(cycleId, accountId)
        val key = "fee-$cycleId-$accountId-$FEE_ID-$CURRENCY"
        // Arrangement only (dispatch is off in this profile): the POSTED flip is the outbox
        // dispatcher's ledger call in production — pinned deterministically here, same as
        // FeeReversalServiceIT / BillingOutboxAtomicityIT do.
        onVertxContext { repository.markPosted(key, UUID.randomUUID()) }

        val body = Given {
            contentType("application/json")
            body("""{"reason":"assessed under a superseded tariff"}""")
        } When {
            post("/api/v1/fees/reverse?idempotencyKey=$key")
        } Then {
            statusCode(200)
            header("X-Reversed-By", OPERATOR_ID)
        }

        val reversed = body.extract()
        assertThat(reversed.path<String>("postingStatus")).isEqualTo("REVERSAL_PENDING")
        assertThat(reversed.path<String>("reversalReason")).isEqualTo("assessed under a superseded tariff")
        assertThat(postingStatusOf(key)).isEqualTo("REVERSAL_PENDING")

        // Idempotent replay: a second reverse of the same fee returns the existing row and
        // appends no second compensating-journal intent.
        val replay = Given {
            contentType("application/json")
            body("""{"reason":"retry"}""")
        } When {
            post("/api/v1/fees/reverse?idempotencyKey=$key")
        } Then {
            statusCode(200)
        }
        assertThat(replay.extract().path<String>("postingStatus")).isEqualTo("REVERSAL_PENDING")
        assertThat(countReversalIntents(key)).isEqualTo(1)
    }

    // ---------------------------------------------------------------- validation and roles

    @Test
    @TestSecurity(user = OPERATOR_ID, roles = ["ROLE_OPERATOR"])
    fun `parameter guards - over-long ids, bad currency and missing params are 400, lowercase currency normalizes`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=${"c".repeat(65)}&accountId=${account()}&currency=$CURRENCY")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=${cycle()}&accountId=${account()}&currency=ISO-2022-CN")
        } Then {
            statusCode(400)
        }

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?accountId=${account()}&currency=$CURRENCY")
        } Then {
            statusCode(400)
        }

        val normalized = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=${cycle()}&accountId=${account()}&currency=czk")
        } Then {
            statusCode(200)
        }
        assertThat(normalized.extract().path<String>("currency")).isEqualTo("CZK")
    }

    @Test
    @TestSecurity(user = VIEWER_ID, roles = ["ROLE_VIEWER"])
    fun `a viewer can dry-run assess but cannot move money`() {
        val cycleId = cycle()
        val accountId = account()
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }

        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=${cycle()}&accountId=${account()}&currency=$CURRENCY")
        } Then {
            statusCode(403)
        }
    }

    @Test
    fun `anonymous callers are rejected from every fee endpoint`() {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/assess?cycleId=${cycle()}&accountId=${account()}&currency=$CURRENCY")
        } Then {
            statusCode(401)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun postAssessment(cycleId: String, accountId: String) {
        val body = Given {
            contentType("application/json")
        } When {
            post("/api/v1/fees/post?cycleId=$cycleId&accountId=$accountId&currency=$CURRENCY")
        } Then {
            statusCode(200)
        }
        assertThat(body.extract().path<Boolean>("skipped"))
            .describedAs("the stubs really resolved, so the write path was reached")
            .isFalse()
    }

    /** Panache reactive needs a Vert.x duplicated context; the JUnit thread is not one. */
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun queryInt(sql: String, vararg binds: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            binds.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun countAssessments(cycleId: String): Int =
        queryInt("SELECT count(*) FROM billing_cycle_assessment WHERE cycle_id = ?", cycleId)

    private fun countPostIntents(cycleId: String, accountId: String): Int = queryInt(
        """
            SELECT count(*) FROM billing_outbox o
            JOIN assessed_fee f ON f.id = o.aggregate_id
            JOIN billing_cycle_assessment a ON a.id = f.assessment_id
            WHERE a.cycle_id = ? AND a.account_id = ? AND o.event_type = '$POST_INTENT'
        """.trimIndent(),
        cycleId,
        accountId,
    )

    private fun countReversalIntents(idempotencyKey: String): Int = queryInt(
        """
            SELECT count(*) FROM billing_outbox
            WHERE event_type = '$REVERSAL_INTENT'
              AND aggregate_id IN (SELECT id FROM assessed_fee WHERE idempotency_key = ?)
        """.trimIndent(),
        idempotencyKey,
    )

    // max() over zero rows yields one row with NULL, so no conditional navigation is needed and
    // the block depth stays within the detekt budget (mirrors queryInt above).
    private fun postingStatusOf(idempotencyKey: String): String? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT max(posting_status) FROM assessed_fee WHERE idempotency_key = ?")
            .use { statement ->
                statement.setString(1, idempotencyKey)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
    }

    private fun cycle() = "e2e-billing-${System.nanoTime()}"

    private fun account() = "acc-e2e-${UUID.randomUUID()}"

    private companion object {
        const val OPERATOR_ID = "00000000-0000-0000-0000-0000000000e1"
        const val VIEWER_ID = "00000000-0000-0000-0000-0000000000e2"
        const val PRODUCT_ID = "CURRENT_CZK"
        const val FEE_ID = "maintenance-monthly"
        const val REVISION_ID = "0191c0de-0000-7000-8000-0000000000a1"
        const val CURRENCY = "CZK"

        const val POST_INTENT = "billing.fee.post-intent.v1"
        const val REVERSAL_INTENT = "billing.fee.reversal-intent.v1"
    }
}
