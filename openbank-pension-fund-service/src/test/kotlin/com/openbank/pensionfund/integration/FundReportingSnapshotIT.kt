// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.ClassificationCorrectionStatus
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.InstrumentClass
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.PositionClassificationCorrection
import com.openbank.pensionfund.infrastructure.persistence.PanachePensionFundStore
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.smallrye.mutiny.Uni
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** A committed correction between two reads must not splice two revisions into one return. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FundReportingSnapshotIT {
    @Inject lateinit var store: PanachePensionFundStore

    @Inject lateinit var vertx: Vertx

    private fun <T> onVertx(block: suspend () -> T): T {
        val future = CompletableFuture<T>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(30, TimeUnit.SECONDS)
    }

    private fun seed(
        fundId: UUID,
        navId: UUID,
        positionId: UUID,
        date: LocalDate,
        at: Instant,
        correction: PositionClassificationCorrection,
    ) {
        onVertx {
            store.commit(
                StoreChanges(
                    funds = listOf(
                        Fund(
                            id = fundId, name = "Snapshot fund", isin = "CZ0001242501",
                            lei = "315700ABCDEF12345678", depositaryReference = "DEP",
                            custodyAccountReference = "CUST-SNAPSHOT", currency = "CZK", riskClass = 3,
                            mandatoryConservative = false, managementFeeRate = BigDecimal.ZERO,
                            launchNavPerUnit = BigDecimal.ONE, status = FundStatus.ACTIVE,
                            createdAt = at, updatedAt = at,
                        ),
                    ),
                    navs = listOf(
                        NavRecord(
                            id = navId, fundId = fundId, valuationDate = date,
                            figures = NavFigures(
                                grossAssets = BigDecimal("100.00"),
                                accruedManagementFee = BigDecimal.ZERO,
                                otherLiabilities = BigDecimal.ZERO,
                                netAssets = BigDecimal("100.00"),
                                unitsOutstanding = BigDecimal("100.000000"),
                                navPerUnit = BigDecimal.ONE,
                            ),
                            status = NavStatus.PUBLISHED, calculatedBy = "maker", calculatedAt = at,
                            approvedBy = "checker", publishedAt = at, positionsRecorded = true,
                        ),
                    ),
                    navPositions = listOf(
                        NavPosition(positionId, navId, "LOAN-1", BigDecimal.ONE, BigDecimal("100.00")),
                    ),
                    classificationCorrections = listOf(correction),
                ),
            )
        }
    }

    @Test
    fun `approved correction committed mid-read appears only in the next snapshot`() {
        val fundId = UUID.randomUUID()
        val navId = UUID.randomUUID()
        val positionId = UUID.randomUUID()
        val correctionId = UUID.randomUUID()
        val date = LocalDate.parse("2026-09-30")
        val at = Instant.parse("2026-09-30T17:00:00Z")
        val correction = PositionClassificationCorrection(
            correctionId,
            positionId,
            navId,
            InstrumentClass.UNCLASSIFIED,
            InstrumentClass.LOAN,
            "source classification",
            "maker",
            at,
        )
        seed(fundId, navId, positionId, date, at, correction)

        val during = onVertx {
            store.reportingSnapshotAfterNavs(fundId, date, date) {
                val context = checkNotNull(Vertx.currentContext())
                val writer = CompletableFuture<Void>()
                val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
                VertxContextSafetyToggle.setContextSafe(duplicated, true)
                val dispatcher = Executor { command ->
                    duplicated.runOnContext { command.run() }
                }.asCoroutineDispatcher()
                CoroutineScope(dispatcher).launch {
                    try {
                        store.commit(
                            StoreChanges(
                                classificationCorrections = listOf(correction.approve("checker", at.plusSeconds(1))),
                            ),
                        )
                        writer.complete(null)
                    } catch (t: Throwable) {
                        writer.completeExceptionally(t)
                    }
                }
                Uni.createFrom().completionStage(writer)
                    .emitOn(Executor { command -> context.runOnContext { command.run() } })
            }
        }
        assertThat(during.corrections.single().status).isEqualTo(ClassificationCorrectionStatus.PROPOSED)
        assertThat(during.positions.single().instrumentClass).isEqualTo(InstrumentClass.UNCLASSIFIED)

        val next = onVertx { store.reportingSnapshot(fundId, date, date) }
        assertThat(next.corrections.single().status).isEqualTo(ClassificationCorrectionStatus.APPROVED)
        assertThat(next.navs.single().id).isEqualTo(during.navs.single().id)
    }
}
