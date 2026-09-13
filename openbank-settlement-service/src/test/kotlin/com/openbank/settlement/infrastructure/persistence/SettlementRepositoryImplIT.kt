// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.persistence

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.it.PostgresTestResource
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
import java.time.Instant
import java.util.UUID

/**
 * Real-Postgres coverage for [SettlementRepositoryImpl] (previously zero-covered): the Panache
 * reactive create/findById/updateStatus round-trip, and the atomic PENDING -> DEBITED
 * claimForProcessing compare-and-set that the legacy settle path relies on to prevent a
 * concurrent double-debit. Reuses the same Testcontainers Postgres resource as
 * SettlementBootSmokeIT (#578 pattern) — no downstream service is called.
 *
 * The repository's suspend functions open a reactive Panache session, which requires an ambient
 * Vert.x context; a plain JUnit test thread has none (same root cause documented on
 * SettlementActivitiesImpl.runOnVertxContext), so every call is bridged via
 * VertxContextSupport.subscribeAndAwait, mirroring AmlOutboxDispatchIT (aml-service).
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SettlementRepositoryImplIT {

    @Inject
    lateinit var repository: SettlementRepository

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun newSettlement(status: SettlementStatus = SettlementStatus.PENDING) = Settlement(
        id = UUID.randomUUID(),
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("321.45"),
        currency = "CZK",
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    /**
     * Real-Postgres cover for the two queries the #5705 stranded gauges call every 30s. They are
     * the only part of this change that can fail purely at runtime: `find("status = ?1 order by
     * createdAt asc")` is an HQL string, so a wrong property name compiles cleanly and throws on
     * first execution — and the caller is a `@Scheduled` tick whose exception nobody is watching,
     * which would leave the gauges frozen at 0 and the alerts permanently, silently quiet. A stub
     * repository cannot detect that; only this can.
     */
    @Test
    fun `countByStatus and oldestCreatedAt answer from a real database`() {
        val status = SettlementStatus.LEDGER_REVERSED // unused by the other tests in this class
        val before = onVertxContext { repository.countByStatus(status) }

        val older = newSettlement(status).copy(createdAt = Instant.parse("2020-01-01T00:00:00Z"))
        val newer = newSettlement(status).copy(createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        onVertxContext { repository.create(newer) }
        onVertxContext { repository.create(older) }

        assertThat(onVertxContext { repository.countByStatus(status) }).isEqualTo(before + 2)
        assertThat(onVertxContext { repository.oldestCreatedAt(status) })
            .describedAs("must be the OLDEST row, not merely any row — inserted newest-first on purpose")
            .isEqualTo(older.createdAt)
    }

    /** An empty state must answer null, which the gauge renders as an age of 0 rather than a stale one. */
    @Test
    fun `oldestCreatedAt is null for a state holding no settlements`() {
        assertThat(onVertxContext { repository.oldestCreatedAt(SettlementStatus.CREDITED_REVERSED) }).isNull()
        assertThat(onVertxContext { repository.countByStatus(SettlementStatus.CREDITED_REVERSED) }).isZero()
    }

    @Test
    fun `create persists the settlement and findById reads it back`() {
        val settlement = newSettlement()

        val created = onVertxContext { repository.create(settlement) }
        val found = onVertxContext { repository.findById(settlement.id) }

        assertThat(created.id).isEqualTo(settlement.id)
        assertThat(found).isNotNull
        assertThat(found!!.payerAccountId).isEqualTo(settlement.payerAccountId)
        assertThat(found.payeeAccountId).isEqualTo(settlement.payeeAccountId)
        assertThat(found.amount).isEqualByComparingTo(settlement.amount)
        assertThat(found.currency).isEqualTo("CZK")
        assertThat(found.status).isEqualTo(SettlementStatus.PENDING)
    }

    @Test
    fun `findById returns null for an unknown id`() {
        assertThat(onVertxContext { repository.findById(UUID.randomUUID()) }).isNull()
    }

    @Test
    fun `updateStatus transitions the row and is reflected on the next read`() {
        val settlement = newSettlement()
        onVertxContext { repository.create(settlement) }

        val updated = onVertxContext { repository.updateStatus(settlement.id, SettlementStatus.BOOKED) }

        assertThat(updated.status).isEqualTo(SettlementStatus.BOOKED)
        assertThat(onVertxContext { repository.findById(settlement.id) }!!.status).isEqualTo(SettlementStatus.BOOKED)
    }

    @Test
    fun `claimForProcessing atomically wins PENDING to DEBITED exactly once`() {
        val settlement = newSettlement()
        onVertxContext { repository.create(settlement) }

        val firstClaim = onVertxContext { repository.claimForProcessing(settlement.id) }
        val secondClaim = onVertxContext { repository.claimForProcessing(settlement.id) }

        assertThat(firstClaim).isTrue()
        assertThat(secondClaim).isFalse()
        assertThat(onVertxContext { repository.findById(settlement.id) }!!.status).isEqualTo(SettlementStatus.DEBITED)
    }

    @Test
    fun `claimForProcessing fails for a settlement that is not PENDING`() {
        val settlement = newSettlement(status = SettlementStatus.BOOKED)
        onVertxContext { repository.create(settlement) }

        val claimed = onVertxContext { repository.claimForProcessing(settlement.id) }

        assertThat(claimed).isFalse()
        assertThat(onVertxContext { repository.findById(settlement.id) }!!.status).isEqualTo(SettlementStatus.BOOKED)
    }
}
