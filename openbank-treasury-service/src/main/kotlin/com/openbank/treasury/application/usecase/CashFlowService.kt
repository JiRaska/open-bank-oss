// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.port.`in`.CashFlowLedgerView
import com.openbank.treasury.application.port.`in`.CashFlowUseCase
import com.openbank.treasury.application.port.`in`.TreasuryCalendarEvent
import com.openbank.treasury.application.port.`in`.TreasuryEventType
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.DealVersionView
import com.openbank.treasury.domain.cashflow.CashFlowConflict
import com.openbank.treasury.domain.cashflow.CashFlowKind
import com.openbank.treasury.domain.cashflow.CashFlowOverride
import com.openbank.treasury.domain.cashflow.OverrideStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CashFlowOverridden
import com.openbank.treasury.domain.model.Deal
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** No such flow or override on the deal. Mapped to 404. */
class CashFlowNotFoundException(message: String) : NoSuchElementException(message)

/**
 * ADR-0344 D4: the stored ledger, its four-eyes overrides and the event calendar. Every rule about
 * who may decide lives on [CashFlowOverride]; this class loads, applies and stores atomically.
 */
@Suppress("TooManyFunctions") // one use case per ledger operation, plus its load/replay helpers
class CashFlowService(
    private val deals: DealRepository,
    private val flows: CashFlowRepository,
    private val schedules: ScheduleService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val newId: () -> UUID = { UUID.randomUUID() },
) : CashFlowUseCase {

    override suspend fun ledger(dealId: UUID, scheduleVersion: Int?): CashFlowLedgerView {
        val deal = load(dealId)
        val version = scheduleVersion ?: deal.scheduleVersion
        if (version > deal.scheduleVersion ||
            version < 0
        ) {
            throw CashFlowNotFoundException("deal $dealId has no schedule version $version")
        }
        val rows = if (version == 0) emptyList() else flows.flows(dealId, version)
        val generatedAt = flows.versions(dealId).lastOrNull()?.createdAt.takeIf { version > 0 }
        return CashFlowLedgerView(
            dealId,
            deal.version,
            version,
            if (version == deal.scheduleVersion) deal.scheduleHash else null,
            generatedAt,
            rows,
            flows.overrides(dealId),
        )
    }

    override suspend fun preview(dealId: UUID): CashFlowLedgerView {
        val deal = load(dealId)
        schedules.requireEffective(deal)
        val g = schedules.generate(deal)
        return CashFlowLedgerView(dealId, deal.version, g.write.scheduleVersion, g.hash, null, g.flows, emptyList())
    }

    override suspend fun proposeOverride(
        dealId: UUID,
        flowId: UUID,
        paymentDate: LocalDate?,
        amount: BigDecimal?,
        reason: String,
        actor: Actor,
        key: String,
    ): CashFlowOverride {
        replayOverride(key, PROPOSE, dealId)?.let { return it }
        val deal = load(dealId)
        val flow = currentFlow(deal, flowId)
        val o = CashFlowOverride.propose(newId(), flow, paymentDate, amount, reason, actor, clock.instant())
        flows.saveOverride(o, null, null, null, CommandKey(key, "$PROPOSE:${o.overrideId}", dealId))
        return o
    }

    override suspend fun approveOverride(
        dealId: UUID,
        flowId: UUID,
        overrideId: UUID,
        actor: Actor,
        key: String,
    ): CashFlowOverride {
        replayOverride(key, APPROVE, dealId)?.let { return it }
        val deal = load(dealId)
        val pending = findOverride(dealId, flowId, overrideId)
        val approved = pending.approve(actor, clock.instant())
        val flow = currentFlow(deal, flowId)
        check(flow.legHash == approved.legHash && flow.slot == approved.slot) {
            "the flow changed since the override was proposed; propose it again against the current schedule"
        }
        val changed = flow.withOverride(approved)
        flows.saveOverride(
            approved,
            OverrideStatus.PENDING,
            changed,
            event(approved, changed),
            CommandKey(key, "$APPROVE:$overrideId", dealId),
        )
        return approved
    }

    override suspend fun rejectOverride(
        dealId: UUID,
        flowId: UUID,
        overrideId: UUID,
        reason: String,
        actor: Actor,
        key: String,
    ): CashFlowOverride {
        replayOverride(key, REJECT, dealId)?.let { return it }
        load(dealId)
        val rejected = findOverride(dealId, flowId, overrideId).reject(actor, reason, clock.instant())
        flows.saveOverride(
            rejected,
            OverrideStatus.PENDING,
            null,
            event(rejected, null),
            CommandKey(key, "$REJECT:$overrideId", dealId),
        )
        return rejected
    }

    override suspend fun conflicts(dealId: UUID): List<CashFlowConflict> {
        load(dealId)
        return flows.conflicts(dealId)
    }

    override suspend fun versions(dealId: UUID): List<DealVersionView> {
        load(dealId)
        return flows.versions(dealId)
    }

    override suspend fun calendar(
        from: LocalDate,
        to: LocalDate,
        bookId: String?,
        types: Set<TreasuryEventType>?,
    ): List<TreasuryCalendarEvent> {
        require(!to.isBefore(from)) { "to must not precede from" }
        require(java.time.temporal.ChronoUnit.DAYS.between(from, to) <= MAX_CALENDAR_DAYS) {
            "at most $MAX_CALENDAR_DAYS days"
        }
        val events = flows.calendarFlows(from, to, bookId).flatMap { cf ->
            val f = cf.flow
            buildList {
                f.fixingDate?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let {
                    add(
                        TreasuryCalendarEvent(
                            it,
                            TreasuryEventType.REFIXING,
                            "fixing for leg ${f.legIndex} period ${f.periodIndex}",
                            f.dealId,
                            f.flowId,
                            BACK_OFFICE_ROLE,
                        ),
                    )
                }
                if (!f.paymentDate.isBefore(from) && !f.paymentDate.isAfter(to)) {
                    val type = when {
                        f.kind == CashFlowKind.INTEREST -> TreasuryEventType.INTEREST_PAYMENT
                        f.kind == CashFlowKind.NOTIONAL_EXCHANGE && f.periodIndex < 0 -> TreasuryEventType.SETTLEMENT
                        f.paymentDate == cf.maturityDate -> TreasuryEventType.MATURITY
                        else -> TreasuryEventType.PRINCIPAL_PAYMENT
                    }
                    val amount = f.amount?.toPlainString() ?: "amount awaiting fixing"
                    add(
                        TreasuryCalendarEvent(
                            f.paymentDate,
                            type,
                            "${cf.product} ${f.kind} ${f.direction} $amount ${f.currency}",
                            f.dealId,
                            f.flowId,
                            if (type == TreasuryEventType.MATURITY) APPROVER_ROLE else BACK_OFFICE_ROLE,
                        ),
                    )
                }
            }
        }
        return events.filter {
            types == null || it.type in types
        }.sortedWith(compareBy({ it.date }, { it.type }, { it.dealId }))
    }

    private fun event(o: CashFlowOverride, flow: com.openbank.treasury.domain.cashflow.CashFlow?) = DealEvent(
        CashFlowOverridden.EVENT_TYPE,
        objectMapper.writeValueAsString(
            CashFlowOverridden(
                dealId = o.dealId,
                flowId = o.flowId,
                overrideId = o.overrideId,
                status = o.status.name,
                paymentDate = o.paymentDate,
                amount = o.amount,
                originalPaymentDate = flow?.originalPaymentDate,
                originalAmount = flow?.originalAmount,
                reason = o.reason,
                proposedBy = o.proposedBy.id,
                decidedBy = checkNotNull(o.decidedBy).id,
                occurredAt = checkNotNull(o.decidedAt),
            ),
        ),
    )

    private suspend fun load(dealId: UUID): Deal = deals.findById(dealId) ?: throw DealNotFoundException(dealId)

    /** Only a flow of the deal's CURRENT schedule version can be overridden. */
    private suspend fun currentFlow(deal: Deal, flowId: UUID) =
        flows.flows(deal.id, null).firstOrNull { it.flowId == flowId }
            ?: throw CashFlowNotFoundException("flow $flowId is not in deal ${deal.id}'s current schedule")

    private suspend fun findOverride(dealId: UUID, flowId: UUID, overrideId: UUID): CashFlowOverride =
        flows.findOverride(overrideId)?.takeIf { it.dealId == dealId && it.flowId == flowId }
            ?: throw CashFlowNotFoundException("override $overrideId not found on flow $flowId")

    /** A replayed key answers with the override as it now stands; a key reused elsewhere is refused. */
    private suspend fun replayOverride(key: String, action: String, dealId: UUID): CashFlowOverride? {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) {
            "Idempotency-Key must be 1..$MAX_KEY_LENGTH characters"
        }
        val prior = deals.findCommand(key) ?: return null
        require(prior.action.startsWith("$action:") && prior.dealId == dealId) {
            "Idempotency-Key was already used for ${prior.action} on another request"
        }
        val id = UUID.fromString(prior.action.substringAfter(':'))
        return checkNotNull(flows.findOverride(id)) { "override $id disappeared" }
    }

    private companion object {
        const val MAX_KEY_LENGTH = 128
        const val MAX_CALENDAR_DAYS = 366L
        const val PROPOSE = "OVERRIDE_PROPOSE"
        const val APPROVE = "OVERRIDE_APPROVE"
        const val REJECT = "OVERRIDE_REJECT"
        const val BACK_OFFICE_ROLE = "ROLE_TREASURY_BACK_OFFICE"
        const val APPROVER_ROLE = "ROLE_TREASURY_APPROVER"
    }
}
