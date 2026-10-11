// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.application.port.StoreChanges
import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.Fund
import com.openbank.pensionfund.domain.model.FundStatus
import com.openbank.pensionfund.domain.model.FundStrategy
import com.openbank.pensionfund.domain.model.StrategyChange
import com.openbank.pensionfund.domain.model.StrategyStatus
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// Funds and strategies are administered together: a strategy is validated against the funds it names.
@ApplicationScoped
@Suppress("TooManyFunctions")
class FundAdministrationService(
    private val store: PensionFundStore,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.pension-fund.strategy-change.minimum-notice-days", defaultValue = "30")
    private val minimumNoticeDays: Long,
) {
    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    suspend fun createFund(definition: FundDefinition): Fund {
        val now = clock.instant()
        val fund = Fund(
            id = Ids.newId(),
            name = definition.name,
            isin = definition.isin,
            lei = definition.lei,
            depositaryReference = definition.depositaryReference,
            custodyAccountReference = definition.custodyAccountReference,
            currency = definition.currency,
            riskClass = definition.riskClass,
            mandatoryConservative = definition.mandatoryConservative,
            managementFeeRate = definition.managementFeeRate,
            launchNavPerUnit = definition.launchNavPerUnit,
            status = FundStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        check(store.funds().none { it.isin == fund.isin }) { "a fund with ISIN ${fund.isin} already exists" }
        store.commit(StoreChanges(funds = listOf(fund)))
        return fund
    }

    suspend fun fund(id: UUID): Fund = store.fund(id) ?: throw NotFoundException("fund $id not found")

    suspend fun funds(): List<Fund> = store.funds()

    suspend fun amendFund(id: UUID, definition: FundDefinition): Fund {
        val current = fund(id)
        require(current.isin == definition.isin && current.currency == definition.currency) {
            "isin and currency identify the fund and cannot change"
        }
        val amended = current.amend(
            name = definition.name,
            depositaryReference = definition.depositaryReference,
            custodyAccountReference = definition.custodyAccountReference,
            riskClass = definition.riskClass,
            mandatoryConservative = definition.mandatoryConservative,
            managementFeeRate = definition.managementFeeRate,
            now = clock.instant(),
        )
        store.commit(StoreChanges(funds = listOf(amended)))
        return amended
    }

    suspend fun closeFund(id: UUID): Fund {
        val current = fund(id)
        check(store.unitsOutstanding(id).signum() == 0) {
            "fund $id still has units outstanding; merge or redeem first"
        }
        check(store.strategies().none { it.status == StrategyStatus.ACTIVE && id in it.fundIds }) {
            "fund $id is still part of an active strategy"
        }
        val closed = current.close(clock.instant())
        store.commit(StoreChanges(funds = listOf(closed)))
        return closed
    }

    suspend fun createStrategy(definition: StrategyDefinition): FundStrategy {
        val now = clock.instant()
        val strategy = FundStrategy(
            id = Ids.newId(),
            name = definition.name,
            allocations = definition.allocations,
            glidePath = definition.glidePath,
            status = StrategyStatus.ACTIVE,
            version = 1,
            createdAt = now,
            updatedAt = now,
        )
        requireActiveFunds(strategy.fundIds)
        store.commit(StoreChanges(strategies = listOf(strategy)))
        return strategy
    }

    suspend fun strategy(id: UUID): FundStrategy =
        store.strategy(id) ?: throw NotFoundException("strategy $id not found")

    suspend fun strategies(): List<FundStrategy> = store.strategies()

    suspend fun allocationFor(strategyId: UUID, yearsToRetirement: Int): List<AllocationTarget> =
        strategy(strategyId).allocationFor(yearsToRetirement)

    suspend fun submitChange(strategyId: UUID, request: StrategyChangeRequest, actor: String): StrategyChange {
        val strategy = strategy(strategyId)
        check(strategy.status == StrategyStatus.ACTIVE) { "strategy $strategyId is closed" }
        val change = StrategyChange.submit(
            id = Ids.newId(),
            strategyId = strategyId,
            proposedAllocations = request.allocations,
            proposedGlidePath = request.glidePath,
            reason = request.reason,
            effectiveDate = request.effectiveDate,
            submittedBy = actor,
            now = clock.instant(),
            today = today(),
            minimumNoticeDays = minimumNoticeDays,
        )
        // Validate the strategy the change would produce NOW, not at application time — a glide
        // path without a 0-years step must be refused at submission, not discovered on the effective date.
        val proposed = strategy.apply(change, clock.instant())
        requireActiveFunds(proposed.fundIds)
        store.commit(StoreChanges(strategyChanges = listOf(change)))
        return change
    }

    suspend fun strategyChange(id: UUID): StrategyChange =
        store.strategyChange(id) ?: throw NotFoundException("strategy change $id not found")

    suspend fun strategyChanges(strategyId: UUID): List<StrategyChange> = store.strategyChanges(strategy(strategyId).id)

    suspend fun approveChange(id: UUID, actor: String): StrategyChange {
        val approved = strategyChange(id).approve(actor, clock.instant(), today(), minimumNoticeDays)
        store.commit(StoreChanges(strategyChanges = listOf(approved)))
        return approved
    }

    suspend fun rejectChange(id: UUID, actor: String): StrategyChange {
        val rejected = strategyChange(id).reject(actor, clock.instant())
        store.commit(StoreChanges(strategyChanges = listOf(rejected)))
        return rejected
    }

    /** Applies an approved change on or after its effective date; refused before it. */
    suspend fun applyChange(id: UUID): FundStrategy {
        val change = strategyChange(id)
        val now = clock.instant()
        val applied = change.markApplied(now, today())
        val strategy = strategy(change.strategyId).apply(change, now)
        requireActiveFunds(strategy.fundIds)
        store.commit(StoreChanges(strategies = listOf(strategy), strategyChanges = listOf(applied)))
        return strategy
    }

    private suspend fun requireActiveFunds(fundIds: Set<UUID>) {
        fundIds.forEach { fundId ->
            val fund = store.fund(fundId)
            require(fund != null && fund.status == FundStatus.ACTIVE) { "fund $fundId is not an active fund" }
        }
    }
}
