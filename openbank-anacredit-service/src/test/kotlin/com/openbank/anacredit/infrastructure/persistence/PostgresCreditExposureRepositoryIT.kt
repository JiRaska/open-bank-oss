// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.anacredit.infrastructure.persistence

import com.openbank.anacredit.domain.model.CounterpartyType
import com.openbank.anacredit.domain.model.CreditExposure
import com.openbank.anacredit.domain.model.InstrumentType
import com.openbank.anacredit.it.PostgresTestResource
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
import java.time.LocalDate
import java.util.Comparator

/**
 * Repository round-trip against a real Testcontainers PostgreSQL (ADR-0037 v2) — the one thing pure
 * domain/unit tests cannot cover: that Flyway's `credit_exposures` schema and reactive Panache
 * actually persist and read back a [CreditExposure].
 *
 * [PostgresCreditExposureRepository] is reactive (`Mutiny.SessionFactory.withSession/withTransaction`
 * bridged to `suspend`), so its calls MUST run on a Vert.x duplicated context — a plain test thread
 * has none and fails with "No current Vertx context found". [onVertxContext] bridges the suspend
 * body via [VertxContextSupport.subscribeAndAwait] (mirrors ledger-service's
 * `JournalPartitionMaintainerIT`). Each test declares an explicit `: Unit` return — `fun x() = expr`
 * inferring a non-`Unit` type silently drops the test under JUnit5/Kotlin.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PostgresCreditExposureRepositoryIT {

    @Inject
    lateinit var repository: PostgresCreditExposureRepository

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun exposure(instrumentId: String, drawnAmount: BigDecimal = BigDecimal("12000.00")) = CreditExposure(
        instrumentId = instrumentId,
        debtorId = "LE-IT-ACME",
        debtorType = CounterpartyType.LEGAL_ENTITY,
        instrumentType = InstrumentType.OVERDRAFT,
        currency = "EUR",
        committedAmount = BigDecimal("40000.00"),
        drawnAmount = drawnAmount,
        committedAmountEur = BigDecimal("40000.00"),
        arrearsAmount = BigDecimal.ZERO,
        defaulted = false,
        originationDate = LocalDate.parse("2025-06-01"),
    )

    @Test
    fun `save then find round-trips every field through Postgres`(): Unit = onVertxContext {
        val saved = repository.upsert(exposure("OD-IT-1"))

        val found = repository.findById("OD-IT-1")

        assertThat(found).isNotNull
        // Postgres NUMERIC(20,2) always reads back scale 2 (e.g. "0.00"), while a BigDecimal.ZERO
        // fixture has scale 0 — same value, different BigDecimal#equals result. Compare BigDecimal
        // fields by numeric value (compareTo), not object equality.
        assertThat(found)
            .usingRecursiveComparison()
            .withComparatorForType(Comparator.naturalOrder(), BigDecimal::class.java)
            .isEqualTo(saved)
    }

    @Test
    fun `upsert on an existing instrumentId updates the row rather than duplicating it`(): Unit = onVertxContext {
        repository.upsert(exposure("OD-IT-2", drawnAmount = BigDecimal("12000.00")))
        repository.upsert(exposure("OD-IT-2", drawnAmount = BigDecimal("18500.00")))

        val found = repository.findById("OD-IT-2")

        assertThat(found).isNotNull
        assertThat(found!!.drawnAmount).isEqualByComparingTo(BigDecimal("18500.00"))
        assertThat(repository.listAll().count { it.instrumentId == "OD-IT-2" }).isEqualTo(1)
    }

    @Test
    fun `findById returns null for an instrument that was never registered`(): Unit = onVertxContext {
        assertThat(repository.findById("OD-IT-DOES-NOT-EXIST")).isNull()
    }

    @Test
    fun `listAll returns every persisted exposure`(): Unit = onVertxContext {
        repository.upsert(exposure("OD-IT-LIST-1"))
        repository.upsert(exposure("OD-IT-LIST-2"))

        val all = repository.listAll().map { it.instrumentId }

        assertThat(all).contains("OD-IT-LIST-1", "OD-IT-LIST-2")
    }
}
