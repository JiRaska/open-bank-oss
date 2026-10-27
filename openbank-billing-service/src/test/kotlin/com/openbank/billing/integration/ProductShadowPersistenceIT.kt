// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.integration

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.application.port.out.StoredRevisionIr
import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.BillingAssessment
import com.openbank.billing.infrastructure.persistence.repository.ProductShadowQueueRepositoryImpl
import com.openbank.billing.infrastructure.persistence.repository.RevisionIrCopyRepositoryImpl
import com.openbank.billing.it.PostgresRedisTestResource
import com.openbank.billing.shadow.ShadowFixtures
import com.openbank.libs.product.WaiveReason
import com.openbank.libs.productrevision.IrSignature
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
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
 * ADR-0352 phase 3 persistence against real Postgres (V11): an assessment commits its shadow input in
 * the SAME transaction, the queue claims it once (SKIP LOCKED), a completed input is never offered
 * again, and a local IR copy is insert-once and immutable in the database.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class ProductShadowPersistenceIT {

    @Inject
    lateinit var assessments: BillingAssessmentRepository

    @Inject
    lateinit var queue: ProductShadowQueueRepositoryImpl

    @Inject
    lateinit var copies: RevisionIrCopyRepositoryImpl

    @Inject
    lateinit var dataSource: DataSource

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun assessment(accountId: String, revisionId: String, withShadow: Boolean) = BillingAssessment(
        cycleId = "2026-10",
        accountId = accountId,
        currency = "CZK",
        skipped = false,
        skipReason = null,
        assessedFees = listOf(
            AssessedFee(
                cycleId = "2026-10",
                accountId = accountId,
                feeId = "MAINT",
                name = "Maintenance",
                currency = "CZK",
                chargedAmount = BigDecimal("4.90"),
                waived = false,
                reason = WaiveReason.CONDITION_NOT_MET,
                productRevisionId = revisionId,
                accountingClass = "CURRENT_ACCOUNT",
            ),
        ),
        shadowInput = if (withShadow) {
            ShadowFixtures.input(
                "500.00",
                listOf(ShadowFixtures.charged()),
                revisionId,
            ).copy(accountId = accountId, currency = "CZK")
        } else {
            null
        },
    )

    @Test
    fun `an assessment queues its shadow input atomically and the queue offers it exactly once`(): Unit =
        onVertxContext {
            val accountId = "acc-shadow-${UUID.randomUUID()}"
            val revisionId = UUID.randomUUID().toString()
            assessments.persistWithPostingIntent(assessment(accountId, revisionId, withShadow = true))

            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT a.xmin::text, f.xmin::text, o.xmin::text, s.xmin::text " +
                        "FROM billing_cycle_assessment a JOIN assessed_fee f ON f.assessment_id = a.id " +
                        "JOIN billing_outbox o ON o.aggregate_id = f.id " +
                        "JOIN billing_product_shadow_input s ON s.assessment_id = a.id WHERE a.account_id = ?",
                ).use { st ->
                    st.setString(1, accountId)
                    st.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(listOf(rs.getString(2), rs.getString(3), rs.getString(4)))
                            .containsOnly(rs.getString(1))
                    }
                }
            }
            // Idempotent replay must not replace frozen facts with a later evaluation.
            val replay = assessment(accountId, revisionId, withShadow = true)
            assessments.persistWithPostingIntent(
                replay.copy(
                    shadowInput = replay.shadowInput!!.copy(
                        evaluatedAt = java.time.Instant.parse("2026-11-01T00:00:00Z"),
                        evaluationZone = "Europe/Prague",
                    ),
                ),
            )
            val claimed = queue.claim(1000).filter { it.input?.accountId == accountId }
            assertThat(claimed).hasSize(1)
            val input = claimed.single().input!!
            assertThat(input.productRevisionId).isEqualTo(revisionId)
            assertThat(input.expectedRevision).isEqualTo(ShadowFixtures.identity(revisionId))
            assertThat(input.balance).isEqualByComparingTo("500.00")
            assertThat(input.evaluatedAt).isEqualTo(java.time.Instant.parse("2026-10-20T12:00:00Z"))
            assertThat(input.evaluationZone).isEqualTo("Z")
            assertThat(input.openedAt).isEqualTo(java.time.Instant.parse("2025-01-01T00:00:00Z"))
            assertThat(input.scheduledCadence).isEqualTo("MONTHLY")
            assertThat(input.scheduledTrigger).isEqualTo("FEE_MAINT")
            assertThat(input.legacyFees.single().chargedAmount).isEqualByComparingTo("4.90")
            // Claimed: a second run within the lease does not see it again.
            assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)

            queue.complete(claimed.single().id, "AGREED", compared = true)
            assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT status, outcome, balance, segment, evaluated_at, evaluation_zone, opened_at, scheduled_cadence, scheduled_trigger FROM billing_product_shadow_input WHERE account_id = ?",
                ).use { st ->
                    st.setString(1, accountId)
                    st.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getString(1)).isEqualTo("DONE")
                        assertThat(rs.getString(2)).isEqualTo("AGREED")
                        // Data minimisation: the account facts are erased once compared.
                        assertThat(rs.getBigDecimal(3)).isNull()
                        assertThat(rs.getString(4)).isNull()
                        for (column in 5..9) assertThat(rs.getObject(column)).isNull()
                    }
                }
            }
        }

    @Test
    fun `an assessment without a shadow input queues nothing`(): Unit = onVertxContext {
        val accountId = "acc-noshadow-${UUID.randomUUID()}"
        assessments.persistWithPostingIntent(assessment(accountId, UUID.randomUUID().toString(), withShadow = false))
        assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)
    }

    @Test
    fun `a retried input comes back only after its back-off`(): Unit = onVertxContext {
        val accountId = "acc-retry-${UUID.randomUUID()}"
        assessments.persistWithPostingIntent(assessment(accountId, UUID.randomUUID().toString(), withShadow = true))
        val claimed = queue.claim(1000).single { it.input?.accountId == accountId }
        queue.retryLater(claimed.id, "REVISION_UNAVAILABLE")
        assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)
    }

    @Test
    fun `a local IR copy is stored once and the database refuses to change it`(): Unit = onVertxContext {
        val revisionId = UUID.randomUUID().toString()
        val first =
            StoredRevisionIr(
                revisionId,
                true,
                "a".repeat(64),
                ShadowFixtures.IR,
                IrSignature("k", "hmac-sha256", "c2ln"),
            )
        copies.store(first)
        val racing = copies.store(first.copy(irJson = "{}"))
        assertThat(racing.irJson).isEqualTo(ShadowFixtures.IR)
        assertThat(copies.find(revisionId)!!.signature!!.signature).isEqualTo("c2ln")
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                val failure = runCatching {
                    st.executeUpdate(
                        "UPDATE billing_product_revision_ir SET ir_json = '{}' WHERE revision_id = '$revisionId'",
                    )
                }.exceptionOrNull()
                assertThat(failure).hasMessageContaining("immutable")
            }
        }
    }

    @Test
    fun `an unmappable shadow input never fails the assessment it shadows`(): Unit = onVertxContext {
        val accountId = "acc-shadow-unmappable-${UUID.randomUUID()}"
        val value = assessment(accountId, UUID.randomUUID().toString(), withShadow = true)
        val unmappable = value.copy(shadowInput = value.shadowInput!!.copy(productRevisionId = "not-a-uuid"))
        assessments.persistWithPostingIntent(unmappable)
        assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM billing_cycle_assessment WHERE account_id = ?").use { st ->
                st.setString(1, accountId)
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getLong(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `a rejected shadow row rolls back the assessment fee and posting intent together`(): Unit = onVertxContext {
        val accountId = "acc-shadow-rollback-${UUID.randomUUID()}"
        val value = assessment(accountId, UUID.randomUUID().toString(), withShadow = true)
        // The shadow is persisted last; its VARCHAR(64) constraint fails in the real transaction.
        val rejected = value.copy(shadowInput = value.shadowInput!!.copy(accountId = "x".repeat(65)))
        assertThat(runCatching { assessments.persistWithPostingIntent(rejected) }.exceptionOrNull()).isNotNull()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT (SELECT count(*) FROM billing_cycle_assessment WHERE account_id = ?), " +
                    "(SELECT count(*) FROM assessed_fee WHERE account_id = ?), " +
                    "(SELECT count(*) FROM billing_outbox WHERE payload::text LIKE ?)",
            ).use { st ->
                st.setString(1, accountId)
                st.setString(2, accountId)
                st.setString(3, "%$accountId%")
                st.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    for (column in 1..3) assertThat(rs.getLong(column)).isZero()
                }
            }
        }
    }

    @Test
    fun `a not-comparable completion keeps the frozen facts so the input can still be compared`(): Unit =
        onVertxContext {
            val accountId = "acc-shadow-keep-${UUID.randomUUID()}"
            assessments.persistWithPostingIntent(assessment(accountId, UUID.randomUUID().toString(), withShadow = true))
            val claimed = queue.claim(1000).single { it.input?.accountId == accountId }
            queue.complete(claimed.id, "VERIFICATION_DISABLED", compared = false)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT status, balance, evaluated_at FROM billing_product_shadow_input WHERE account_id = ?",
                ).use { st ->
                    st.setString(1, accountId)
                    st.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getString(1)).isEqualTo("DONE")
                        assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("500.00")
                        assertThat(rs.getObject(3)).isNotNull()
                    }
                }
            }
        }

    @Test
    fun `a stored row that cannot be mapped is still claimed, with no input, instead of blocking the queue`(): Unit =
        onVertxContext {
            val accountId = "acc-shadow-corrupt-${UUID.randomUUID()}"
            assessments.persistWithPostingIntent(assessment(accountId, UUID.randomUUID().toString(), withShadow = true))
            val id = dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE billing_product_shadow_input SET legacy_fees = '[{\"feeId\":1}]' " +
                        "WHERE account_id = ? RETURNING id",
                ).use { st ->
                    st.setString(1, accountId)
                    st.executeQuery().use { rs ->
                        assertThat(rs.next()).isTrue()
                        rs.getObject(1, UUID::class.java)
                    }
                }
            }
            val claimed = queue.claim(1000).single { it.id == id }
            assertThat(claimed.input).isNull()
        }

    @Test
    fun `a row locked by another session is skipped, never waited on or claimed twice`(): Unit = onVertxContext {
        val accountId = "acc-shadow-locked-${UUID.randomUUID()}"
        assessments.persistWithPostingIntent(assessment(accountId, UUID.randomUUID().toString(), withShadow = true))
        dataSource.connection.use { other ->
            other.autoCommit = false
            other.prepareStatement(
                "SELECT id FROM billing_product_shadow_input WHERE account_id = ? FOR UPDATE",
            ).use { st ->
                st.setString(1, accountId)
                st.executeQuery().use { rs -> assertThat(rs.next()).isTrue() }
            }
            // The second session's claim returns at once, without the row the first session holds.
            assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)
            other.rollback()
        }
        // Released: now exactly one claim gets it.
        assertThat(queue.claim(1000).count { it.input?.accountId == accountId }).isEqualTo(1)
        assertThat(queue.claim(1000).map { it.input?.accountId }).doesNotContain(accountId)
    }

    @Test
    fun `legacy identity stays unknown and real database round trips an independent complete identity`(): Unit =
        onVertxContext {
            val revisionId = UUID.randomUUID().toString()
            val first = StoredRevisionIr(
                revisionId,
                true,
                ShadowFixtures.identity(revisionId).revisionHash,
                ShadowFixtures.IR,
                IrSignature("k", "hmac-sha256", "c2ln"),
            )
            copies.store(first)
            assertThat(copies.find(revisionId)!!.identity).isNull()
            dataSource.connection.use { c ->
                val failure = runCatching {
                    c.prepareStatement(
                        "UPDATE billing_product_revision_ir SET actual_revision_identity = ?, ir_json = ? WHERE revision_id = ?::uuid",
                    ).use { statement ->
                        statement.setString(
                            1,
                            "{\"revisionId\":\"$revisionId\",\"revisionHash\":\"${first.revisionHash}\"}",
                        )
                        statement.setString(2, "{}")
                        statement.setString(3, revisionId)
                        statement.executeUpdate()
                    }
                }.exceptionOrNull()
                assertThat(failure).hasMessageContaining("immutable")
            }
            assertThat(copies.find(revisionId)).isEqualTo(first)
            val complete = first.copy(identity = ShadowFixtures.identity(revisionId))
            assertThat(copies.store(complete)).isEqualTo(complete)
            assertThat(copies.find(revisionId)).isEqualTo(complete)
            val conflicting = complete.copy(identity = complete.identity!!.copy(revisionNumber = 999))
            assertThat(copies.store(conflicting)).isEqualTo(complete)
            dataSource.connection.use { c ->
                val failure = runCatching {
                    c.createStatement().use { st ->
                        st.executeUpdate(
                            "UPDATE billing_product_revision_ir SET actual_revision_identity = NULL WHERE revision_id = '$revisionId'",
                        )
                    }
                }.exceptionOrNull()
                assertThat(failure).hasMessageContaining("immutable")
            }
        }

    @Test
    fun `a pre-migration queue input has unknown identity after the real database round trip`(): Unit = onVertxContext {
        val accountId = "legacy-pin-${UUID.randomUUID()}"
        val assessment = assessment(accountId, UUID.randomUUID().toString(), withShadow = true)
        assessments.persistWithPostingIntent(
            assessment.copy(shadowInput = assessment.shadowInput!!.copy(expectedRevision = null)),
        )
        val input = queue.claim(1000).single { it.input?.accountId == accountId }.input!!
        assertThat(input.expectedRevision).isNull()
    }
}
