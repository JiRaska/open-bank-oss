// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.integration

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.infrastructure.catalog.CatalogInterestEventResolution
import com.openbank.interest.infrastructure.catalog.CatalogInterestProfile
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncOutcome
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncRepository
import com.openbank.interest.infrastructure.client.CatalogEventClientResponse
import com.openbank.interest.infrastructure.scheduler.ProductShadowScheduler
import com.openbank.interest.shadow.ShadowFixtures
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * The interest product-IR shadow against the real schema (V21) and the real InterestService: an accrual
 * committed by the legacy path is enqueued AFTER its transaction, matched to the account's ADR-0341 pin,
 * verified (HMAC, %test) and re-decided by the IR. A revision whose IR says the same rate agrees; one whose
 * IR says another rate diverges, with both amounts on the queue row.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
internal class ProductShadowPersistenceIT {
    @Inject lateinit var sync: CatalogInterestSyncRepository

    @Inject lateinit var accrue: AccrueInterestUseCase

    @Inject lateinit var scheduler: ProductShadowScheduler

    @Inject lateinit var dataSource: DataSource

    /** Publishes a EUR 3 % catalog revision and gives it a signed local IR copy whose rate is [irRate]. */
    private fun publishWithIr(irRate: String): String {
        val specification = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val profile = CatalogInterestProfile(
            revisionId = revisionId, offeringId = UUID.randomUUID(), specificationId = specification,
            schemaId = "org.openbank.banking.deposit", schemaVersion = 2, contentHash = "c".repeat(64),
            currency = "EUR", annualRate = BigDecimal("0.030000000000000000"), dayCount = DayCount.ACT_365,
            effectiveFrom = LocalDate.of(2027, 1, 1), effectiveTo = null,
        )
        val event = CatalogEventClientResponse(
            id = UUID.randomUUID(),
            aggregateType = "catalog.revision",
            eventType = "com.openbank.catalog.revision_published",
            aggregateId = revisionId,
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC),
        )
        val outcome = onEventLoopUni {
            sync.record(CatalogInterestEventResolution.applied(event, profile), "shadow-${UUID.randomUUID()}")
        }
        assertThat(outcome).isEqualTo(CatalogInterestSyncOutcome.APPLIED)

        val ir = ShadowFixtures.withRate(irRate).replace("\"CZK\"", "\"EUR\"")
        val hash = ProductIrParser.parse(ir).revisionHash
        val sig = ShadowFixtures.signer.sign(hash)
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO interest_product_revision_ir " +
                    "(revision_id, has_ir, revision_hash, ir_json, key_id, algorithm, signature, fetched_at) " +
                    "VALUES (?, true, ?, ?, ?, ?, ?, now())",
            ).use {
                it.setObject(1, revisionId)
                it.setString(2, hash)
                it.setString(3, ir)
                it.setString(4, sig.keyId)
                it.setString(5, sig.algorithm)
                it.setString(6, sig.signature)
                it.executeUpdate()
            }
        }
        return specification.toString()
    }

    private fun shadowRow(accrualId: UUID): Triple<String?, BigDecimal?, BigDecimal?> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT outcome, legacy_amount, ir_amount FROM interest_product_shadow_input " +
                "WHERE source_kind = 'ACCRUAL' AND source_id = ?",
        ).use { st ->
            st.setObject(1, accrualId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "accrual $accrualId was never enqueued" }
                Triple(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3))
            }
        }
    }

    @Test
    fun `a committed accrual is enqueued after its transaction and agrees, or diverges, with the pinned IR`() {
        val agreeing = publishWithIr("0.0300000000")
        val diverging = publishWithIr("0.0310000000")
        val date = LocalDate.of(2027, 3, 1)
        val a =
            onEventLoopUni {
                accrue.accrue(AccrualRequest(UUID.randomUUID(), agreeing, BigDecimal("1000.00"), "EUR", date))
            }
        val b =
            onEventLoopUni {
                accrue.accrue(AccrualRequest(UUID.randomUUID(), diverging, BigDecimal("1000.00"), "EUR", date))
            }

        // Drain until both are done: other IT classes' rows share this database and this queue.
        repeat(MAX_RUNS) { onEventLoop { scheduler.runOnce() } }

        assertThat(shadowRow(a.id)).isEqualTo(Triple("AGREED", BigDecimal("0.082192"), BigDecimal("0.082192")))
        val (outcome, legacy, ir) = shadowRow(b.id)
        assertThat(outcome).isEqualTo("DIVERGED")
        assertThat(legacy).isEqualByComparingTo("0.082192")
        // 1000 * round10(0.031 / 365) = 1000 * 0.0000849315
        assertThat(ir).isEqualByComparingTo("0.084932")

        // Idempotent intake: a second run enqueues neither accrual again.
        onEventLoop { scheduler.runOnce() }
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM interest_product_shadow_input WHERE source_id IN (?, ?)").use {
                it.setObject(1, a.id)
                it.setObject(2, b.id)
                it.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(2)
                }
            }
        }
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun <T> onEventLoopUni(work: () -> Uni<T>): T = VertxContextSupport.subscribeAndAwait(work)

    private companion object {
        const val MAX_RUNS = 3
    }
}
