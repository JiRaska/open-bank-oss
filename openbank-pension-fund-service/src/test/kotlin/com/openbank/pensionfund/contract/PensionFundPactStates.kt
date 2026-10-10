// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.contract

import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.InstrumentClass
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavPosition
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.UnitTransaction
import com.openbank.pensionfund.domain.model.UnitTransactionType
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Provider states shared by the git and broker replays, so the two cannot drift apart. */
object PensionFundPactStates {
    /** Must match TaxReportingPensionFundPactConsumerTest (openbank-tax-reporting-service). */
    val FUND_ID: UUID = UUID.fromString("f0a7c1e2-0000-4000-8000-000000012425")
    const val FUND_STATE = "a pension fund with a published September 2026 NAV exists"

    /** The state name every consumer of this provider uses for its missing-identity interaction. */
    const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

    /** One fund, one published 30 September NAV struck on one position, one subscription priced at it. */
    fun fundWithSeptemberNav(vertx: Vertx, store: PensionFundStore) = runOnVertxContext(vertx) {
        if (store.fund(FUND_ID) != null) return@runOnVertxContext
        val at = Instant.parse("2026-09-30T17:00:00Z")
        val fund = Fund(
            id = FUND_ID, name = "Pact Fund", isin = "CZ0001242500", lei = "315700ABCDEF12345678",
            depositaryReference = "DEP", custodyAccountReference = "CUST-PACT", currency = "CZK", riskClass = 3,
            mandatoryConservative = false, managementFeeRate = BigDecimal("0.008"), launchNavPerUnit = BigDecimal.ONE,
            status = FundStatus.ACTIVE, createdAt = at, updatedAt = at,
        )
        val navId = UUID.fromString("f0a7c1e2-0000-4000-8000-0000000a0930")
        val nav = NavRecord(
            id = navId, fundId = FUND_ID, valuationDate = LocalDate.parse("2026-09-30"),
            figures = NavFigures(
                grossAssets = BigDecimal("1000.00"),
                accruedManagementFee = BigDecimal("0.00"),
                otherLiabilities = BigDecimal("0.00"),
                netAssets = BigDecimal("1000.00"),
                unitsOutstanding = BigDecimal("1000.000000"),
                navPerUnit = BigDecimal("1.000000"),
            ),
            status = NavStatus.PUBLISHED, calculatedBy = "maker", calculatedAt = at, approvedBy = "checker",
            publishedAt = at, positionsRecorded = true,
        )
        val subscription = UnitTransaction(
            id = UUID.randomUUID(), orderId = null, contractId = UUID.randomUUID(), fundId = FUND_ID,
            type = UnitTransactionType.SUBSCRIBE, units = BigDecimal("1000.000000"), amount = BigDecimal("1000.00"),
            navId = navId, navPerUnit = BigDecimal("1.000000"), pricedAt = at,
        )
        store.commit(
            StoreChanges(
                funds = listOf(fund),
                navs = listOf(nav),
                transactions = listOf(subscription),
                navPositions = listOf(
                    NavPosition(
                        UUID.fromString("f0a7c1e2-0000-4000-8000-0000000b0930"),
                        navId,
                        "CZ-BOND-1",
                        BigDecimal("10"),
                        BigDecimal("100"),
                        InstrumentClass.DEBT_SECURITY,
                    ),
                ),
            ),
        )
    }

    /** Reactive Panache needs a Vert.x context, which Pact-JVM's state callback thread lacks. */
    private fun runOnVertxContext(vertx: Vertx, block: suspend () -> Unit) {
        val future = CompletableFuture<Unit>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            try {
                block()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        future.get(10, TimeUnit.SECONDS)
    }
}
