// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.integration

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.ManageRateConfigUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.CatalogSourcedRateException
import com.openbank.interest.domain.model.DayCount
import com.openbank.interest.infrastructure.catalog.CatalogInterestEventResolution
import com.openbank.interest.infrastructure.catalog.CatalogInterestProfile
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncOutcome
import com.openbank.interest.infrastructure.catalog.CatalogInterestSyncRepository
import com.openbank.interest.infrastructure.client.CatalogEventClientResponse
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0341 against the real schema: a superseding catalog revision reprices NEW accounts only. An
 * account pinned to revision A keeps A's rate after revision B is synced and effective.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_interest_it")],
)
internal class RevisionPinSupersedeIT {
    @Inject lateinit var sync: CatalogInterestSyncRepository

    @Inject lateinit var accrue: AccrueInterestUseCase

    @Inject lateinit var rates: ManageRateConfigUseCase

    private val specification = UUID.randomUUID()

    private fun publish(rate: String, from: LocalDate, cursor: String): UUID {
        val revisionId = UUID.randomUUID()
        val profile = CatalogInterestProfile(
            revisionId = revisionId, offeringId = UUID.randomUUID(), specificationId = specification,
            schemaId = "org.openbank.banking.deposit", schemaVersion = 2, contentHash = "b".repeat(64),
            currency = "EUR", annualRate = BigDecimal(rate), dayCount = DayCount.ACT_365,
            effectiveFrom = from, effectiveTo = null,
        )
        val event = CatalogEventClientResponse(
            id = UUID.randomUUID(),
            aggregateType = "catalog.revision",
            eventType = "com.openbank.catalog.revision_published",
            aggregateId = revisionId,
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC),
        )
        val outcome = onEventLoop { sync.record(CatalogInterestEventResolution.applied(event, profile), cursor) }
        assertThat(outcome).isEqualTo(CatalogInterestSyncOutcome.APPLIED)
        return revisionId
    }

    private fun accrueOn(account: UUID, date: LocalDate) = onEventLoop {
        accrue.accrue(AccrualRequest(account, specification.toString(), BigDecimal("1000.00"), "EUR", date))
    }

    @Test
    fun `superseded revision does not change an existing account's rate but prices a new account`() {
        publish("0.020000000000000000", LocalDate.of(2027, 1, 1), "c-a")
        val existing = UUID.randomUUID()
        val first = accrueOn(existing, LocalDate.of(2027, 1, 15))

        publish("0.030000000000000000", LocalDate.of(2027, 2, 1), "c-b")
        val later = accrueOn(existing, LocalDate.of(2027, 2, 10))
        val brandNew = accrueOn(UUID.randomUUID(), LocalDate.of(2027, 2, 10))

        // 1000 * 0.02 / 365 and 1000 * 0.03 / 365, at the accrual's 6-digit scale.
        assertThat(later.configId).isEqualTo(first.configId)
        assertThat(later.accruedAmount).isEqualByComparingTo("0.054795")
        assertThat(brandNew.configId).isNotEqualTo(first.configId)
        assertThat(brandNew.accruedAmount).isEqualByComparingTo("0.082192")
    }

    @Test
    fun `admin API refuses to deactivate a catalog-sourced rate row`() {
        publish("0.020000000000000000", LocalDate.of(2027, 3, 1), "c-c")
        val cached = onEventLoop { rates.listConfigs(specification.toString()) }.first()

        val failure = runCatching { onEventLoop { rates.deactivateConfig(cached.id) } }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CatalogSourcedRateException::class.java)
        assertThat(onEventLoop { rates.getConfig(cached.id) }!!.active).isTrue()
    }

    private fun <T> onEventLoop(work: () -> Uni<T>): T = VertxContextSupport.subscribeAndAwait(work)
}
