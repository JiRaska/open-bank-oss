// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.event

import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Permanent proof that domestic-payment won the create-vs-finalizer race without a payment. */
@Suppress("LongParameterList")
data class DelegatedSpendFinalizedAbsentEvent(
    val reservationId: UUID,
    val delegationId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: String,
    val resourceId: UUID,
    val operationType: String,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKeyHash: String,
    val reservationState: String,
    val reservationVersion: Long,
    val finalizedAt: Instant,
    val occurredAt: Instant,
    val eventType: String = EVENT_TYPE,
    val sourceService: String = SOURCE_SERVICE,
    val version: Long = SCHEMA_VERSION,
) {
    companion object {
        const val EVENT_TYPE = "DELEGATED_SPEND_FINALIZED_ABSENT"
        const val SOURCE_SERVICE = "domestic-payment"
        const val SCHEMA_VERSION = 1L
    }
}

fun DelegatedSpendBinding.toFinalizedAbsentEvent(): DelegatedSpendFinalizedAbsentEvent {
    val finalized = checkNotNull(finalizedAt) { "Only a finalized binding can emit this event" }
    check(bindingState == DelegatedSpendBindingState.FINALIZED_ABSENT) {
        "Only a FINALIZED_ABSENT binding can emit this event"
    }
    check(paymentId == null) { "A finalized-absent binding cannot reference a payment" }
    check(
        snapshot.reservationState == DelegatedSpendReservationState.RESERVED &&
            snapshot.reservationVersion == DelegatedSpendReservationSnapshot.RESERVED_VERSION,
    ) { "Only a pending RESERVED revision 1 can be finalized absent locally" }
    return DelegatedSpendFinalizedAbsentEvent(
        reservationId = snapshot.reservationId,
        delegationId = snapshot.delegationId,
        grantorPartyId = snapshot.grantorPartyId,
        granteePartyId = snapshot.granteePartyId,
        resourceType = snapshot.resourceType,
        resourceId = snapshot.resourceId,
        operationType = snapshot.operationType,
        amount = snapshot.amount,
        currency = snapshot.currency,
        idempotencyKeyHash = snapshot.idempotencyKeyHash,
        reservationState = snapshot.reservationState.name,
        reservationVersion = snapshot.reservationVersion,
        finalizedAt = finalized,
        occurredAt = finalized,
    )
}
