// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.`in`

import com.openbank.domestic.application.port.out.ReservationProjectionApplyResult
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateDomesticPaymentCommand(
    val idempotencyKey: String,
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val messageForPayee: String?,
    val priority: DomesticPaymentPriority,
    val transferScope: DomesticTransferScope? = null,
    val technicalAccountCode: String? = null,
    val statementLabel: String?,
    val endToEndId: String?,
    val actorId: UUID? = null,
    /** Stable authenticated-principal scope (issuer + subject where available), used only for replay binding. */
    val actorScope: String? = null,
    /** Delegation grant that authorized this payment; present only together with [reservationId]. */
    val delegationId: UUID? = null,
    /** Spend reservation durably bound to this payment; present only together with [delegationId]. */
    val reservationId: UUID? = null,
    /** Trusted inbound synthetic taint, copied into the durable outbox boundary. */
    val synthetic: Boolean = false,
) {
    init {
        require((delegationId == null) == (reservationId == null)) {
            "delegationId and reservationId must either both be present or both be absent"
        }
    }
}

/** Result of create-or-replay; lets the REST edge preserve the replay signal without trusting a cache. */
data class CreateDomesticPaymentResult(val payment: DomesticPayment, val replayed: Boolean)

data class ListDomesticPaymentsQuery(
    val status: DomesticPaymentStatus? = null,
    val debtorAccountId: UUID? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

data class TransitionDomesticPaymentStatusCommand(
    val paymentId: UUID,
    val targetStatus: DomesticPaymentStatus,
    val rejectReason: DomesticRejectReason? = null,
    val rejectDetail: String? = null,
)

interface DomesticPaymentUseCase {
    suspend fun createPayment(command: CreateDomesticPaymentCommand): CreateDomesticPaymentResult
    suspend fun getPayment(paymentId: UUID): DomesticPayment
    suspend fun listPayments(query: ListDomesticPaymentsQuery): List<DomesticPayment>
    suspend fun transitionStatus(command: TransitionDomesticPaymentStatusCommand): DomesticPayment
}

/**
 * Workload-only seam for the future trusted customer-edge endpoint.
 *
 * It is deliberately a separate port from [DomesticPaymentUseCase]: the existing public owner
 * endpoint cannot accidentally opt into delegated identity/context merely by populating fields on
 * [CreateDomesticPaymentCommand]. No REST adapter is wired to this port in the expand-first stage.
 */
interface DelegatedDomesticPaymentUseCase {
    /**
     * [reservationId] is the only trusted selector supplied by the future workload adapter.
     * Actor/delegation fields on [command] are untrusted and are replaced from the durable local
     * reservation projection before fingerprinting or persistence.
     */
    suspend fun createDelegatedPayment(
        reservationId: UUID,
        command: CreateDomesticPaymentCommand,
    ): DelegatedDomesticPaymentResult
}

sealed interface DelegatedDomesticPaymentResult {
    data class Accepted(val result: CreateDomesticPaymentResult) : DelegatedDomesticPaymentResult

    /** Projection has not consumed the compacted RESERVED snapshot yet; future HTTP mapping = 425. */
    data object ReservationProjectionPending : DelegatedDomesticPaymentResult

    /** Finalizer/terminal snapshot won before create; the tombstone is permanent; future mapping = 410. */
    data object ReservationFinalizedAbsent : DelegatedDomesticPaymentResult

    /** Supplied payment context does not equal the immutable reservation tuple; future mapping = 409. */
    data class ReservationMismatch(val reason: String) : DelegatedDomesticPaymentResult

    /** Account-service could not prove debit-account ownership; retryable future mapping = 503. */
    data object AccountAuthorityUnavailable : DelegatedDomesticPaymentResult
}

interface ApplyDelegatedSpendReservationStateUseCase {
    suspend fun apply(snapshot: DelegatedSpendReservationSnapshot): ReservationProjectionApplyResult
}

interface FinalizeAbsentDelegatedSpendUseCase {
    suspend fun finalizeBefore(cutoff: Instant, limit: Int): Int
}
