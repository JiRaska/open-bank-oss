// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.cashflow

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowConflict
import com.openbank.treasury.domain.cashflow.CashFlowKind
import com.openbank.treasury.domain.cashflow.CashFlowOverride
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.cashflow.FlowSlot
import com.openbank.treasury.domain.cashflow.OverrideStatus
import com.openbank.treasury.domain.cashflow.RateSourceWire
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType

internal fun CashFlowEntity.fill(f: CashFlow) = apply {
    flowId = f.flowId
    dealId = f.dealId
    scheduleVersion = f.scheduleVersion
    legIndex = f.legIndex
    periodIndex = f.periodIndex
    kind = f.kind.name
    direction = f.direction.name
    currency = f.currency
    accrualStart = f.accrualStart
    accrualEnd = f.accrualEnd
    fixingDate = f.fixingDate
    paymentDate = f.paymentDate
    notional = f.notional
    rate = f.rate
    rateSource = RateSourceWire.encode(f.rateSource)
    amount = f.amount
    state = f.state.name
    legHash = f.legHash
    manual = f.manual
    originalPaymentDate = f.originalPaymentDate
    originalAmount = f.originalAmount
    overrideId = f.overrideId
    // Deliberately NOT written here: paymentId and the fixing provenance (fixingSource,
    // fixingSha256, fixedAt). Each has its own narrow, guarded UPDATE (FixingFlowRepositoryImpl,
    // PaymentRepositoryImpl) so a writer holding a stale copy of the row can never erase them.
}

internal fun CashFlowEntity.toDomain() = CashFlow(
    flowId = flowId,
    dealId = dealId,
    scheduleVersion = scheduleVersion,
    legIndex = legIndex,
    periodIndex = periodIndex,
    kind = CashFlowKind.valueOf(kind),
    direction = FlowDirection.valueOf(direction),
    currency = currency,
    accrualStart = accrualStart,
    accrualEnd = accrualEnd,
    fixingDate = fixingDate,
    paymentDate = paymentDate,
    notional = notional,
    rate = rate,
    rateSource = RateSourceWire.decode(rateSource),
    amount = amount,
    state = CashFlowState.valueOf(state),
    legHash = legHash,
    manual = manual,
    originalPaymentDate = originalPaymentDate,
    originalAmount = originalAmount,
    overrideId = overrideId,
    fixingSource = fixingSource,
    fixingSha256 = fixingSha256,
    fixedAt = fixedAt,
    paymentId = paymentId,
)

internal fun CashFlowOverrideEntity.fill(o: CashFlowOverride) = apply {
    overrideId = o.overrideId
    dealId = o.dealId
    flowId = o.flowId
    legIndex = o.slot.legIndex
    periodIndex = o.slot.periodIndex
    kind = o.slot.kind.name
    legHash = o.legHash
    paymentDate = o.paymentDate
    amount = o.amount
    reason = o.reason
    proposedBy = o.proposedBy.id
    proposedByType = o.proposedBy.type.name
    proposedAt = o.proposedAt
    status = o.status.name
    decidedBy = o.decidedBy?.id
    decidedByType = o.decidedBy?.type?.name
    decidedAt = o.decidedAt
    decisionReason = o.decisionReason
}

internal fun CashFlowOverrideEntity.toDomain() = CashFlowOverride(
    overrideId = overrideId,
    dealId = dealId,
    flowId = flowId,
    slot = FlowSlot(legIndex, periodIndex, CashFlowKind.valueOf(kind)),
    legHash = legHash,
    paymentDate = paymentDate,
    amount = amount,
    reason = reason,
    proposedBy = Actor(proposedBy, ActorType.valueOf(proposedByType)),
    proposedAt = proposedAt,
    status = OverrideStatus.valueOf(status),
    decidedBy = decidedBy?.let { Actor(it, ActorType.valueOf(decidedByType ?: ActorType.HUMAN.name)) },
    decidedAt = decidedAt,
    decisionReason = decisionReason,
)

internal fun CashFlowConflictEntity.fill(c: CashFlowConflict) = apply {
    conflictId = c.conflictId
    dealId = c.dealId
    overrideId = c.overrideId
    legIndex = c.slot.legIndex
    periodIndex = c.slot.periodIndex
    kind = c.slot.kind.name
    fromScheduleVersion = c.fromScheduleVersion
    toScheduleVersion = c.toScheduleVersion
    detectedAt = c.detectedAt
    reason = c.reason
}

internal fun CashFlowConflictEntity.toDomain() = CashFlowConflict(
    conflictId = conflictId,
    dealId = dealId,
    overrideId = overrideId,
    slot = FlowSlot(legIndex, periodIndex, CashFlowKind.valueOf(kind)),
    fromScheduleVersion = fromScheduleVersion,
    toScheduleVersion = toScheduleVersion,
    detectedAt = detectedAt,
    reason = reason,
)
