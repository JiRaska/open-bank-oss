// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.`in`.ReserveSpendCommand
import com.openbank.delegation.application.port.`in`.ReserveSpendResult
import com.openbank.delegation.application.port.`in`.ReserveSpendUseCase
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.ReserveOutcome
import com.openbank.delegation.application.port.out.SpendReservationRepository
import com.openbank.delegation.domain.event.EventMoney
import com.openbank.delegation.domain.event.SpendConfirmed
import com.openbank.delegation.domain.event.SpendReleased
import com.openbank.delegation.domain.event.SpendReserved
import com.openbank.delegation.domain.model.CountedSpend
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.SpendCeilings
import com.openbank.delegation.domain.model.SpendDecision
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.delegation.domain.model.SpendWindows
import com.openbank.libs.domain.event.DomainEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** The reserve was refused; [decision] names which ceiling and how much is left under it. */
class SpendReservationRefusedException(val decision: SpendDecision.Refused) :
    RuntimeException("spend refused by ${decision.reason} ceiling")

class SpendReservationNotFoundException(id: UUID) : RuntimeException("Spend reservation not found: $id")

/** A settle that cannot be replayed into the state the caller asked for. */
class SpendReservationStateException(message: String) : RuntimeException(message)

class SpendReservationIdempotencyConflictException :
    RuntimeException("idempotencyKey already belongs to a different immutable reservation tuple")

class SpendReservationStateStreamUnavailableException :
    RuntimeException("domestic-payment reservation state stream is not active")

/**
 * ADR-0249 D3 — the cumulative-spend counter, in ONE place.
 *
 * Why here and not in each payment rail: one grant is spendable through domestic payments, SEPA,
 * instant and cards. A counter per rail cannot see the others, so each would enforce a ceiling the
 * customer does not have. The grant is the only thing that sees all of it.
 */
@ApplicationScoped
class SpendReservationService(
    private val delegationRepository: DelegationRepository,
    private val reservationRepository: SpendReservationRepository,
    private val clock: Clock,
) : ReserveSpendUseCase {

    @Inject
    constructor(
        delegationRepository: DelegationRepository,
        reservationRepository: SpendReservationRepository,
    ) : this(delegationRepository, reservationRepository, Clock.systemUTC())

    override suspend fun reserve(command: ReserveSpendCommand): ReserveSpendResult {
        val now = OffsetDateTime.now(clock)
        val grant = loadForGrantee(command.delegationId, command.callerPartyId)
        val candidate = SpendReservation(
            grantId = grant.id,
            amount = command.amount,
            idempotencyKey = command.idempotencyKey,
            operationType = command.operationType,
            createdAt = now,
        )
        val outcome = reservationRepository.reserve(
            candidate = candidate,
            window = SpendWindows.windowAt(now),
            // ADR-0249 D4 (#5728). `now` is this call's real clock reading, not a default: an
            // `occurredAt` that defaults is the Instant.EPOCH shape (#3874/#3883) that every
            // isNotNull() assertion in the fleet agreed with while the trail claimed 1970.
            auditEvent = { reservation ->
                SpendReserved(
                    aggregateId = grant.id,
                    reservationId = reservation.id,
                    grantorPartyId = grant.grantorPartyId,
                    granteePartyId = grant.granteePartyId,
                    amount = EventMoney(reservation.amount.amount, reservation.amount.currency.code),
                    idempotencyKey = reservation.idempotencyKey,
                    occurredAt = now.toInstant(),
                )
            },
        ) { lockedGrant, counted -> evaluateLockedGrant(command, lockedGrant, counted, now) }

        return when (outcome) {
            is ReserveOutcome.Created -> ReserveSpendResult(outcome.reservation, replayed = false)
            is ReserveOutcome.Replayed -> ReserveSpendResult(outcome.reservation, replayed = true)
            ReserveOutcome.IdempotencyConflict -> throw SpendReservationIdempotencyConflictException()
            ReserveOutcome.StateStreamUnavailable -> throw SpendReservationStateStreamUnavailableException()
            is ReserveOutcome.Refused -> throw SpendReservationRefusedException(outcome.decision)
        }
    }

    private fun evaluateLockedGrant(
        command: ReserveSpendCommand,
        lockedGrant: DelegationGrant,
        counted: CountedSpend,
        now: OffsetDateTime,
    ): SpendDecision {
        if (command.operationType == SpendReservationOperationType.DOMESTIC_PAYMENT &&
            (
                lockedGrant.resourceType != DelegationResourceType.ACCOUNT ||
                    !lockedGrant.hasCapability(DelegationCapability.ACCOUNT_INITIATE_PAYMENT)
                )
        ) {
            return SpendDecision.Refused(
                com.openbank.delegation.domain.model.SpendRefusalReason.NO_SPEND_CAPABILITY,
            )
        }
        return SpendCeilings.evaluate(lockedGrant, command.amount, counted, now)
    }

    override suspend fun confirm(
        delegationId: UUID,
        reservationId: UUID,
        callerPartyId: CallerPartyId,
    ): SpendReservation = settle(delegationId, reservationId, callerPartyId, SpendReservationState.CONFIRMED)

    override suspend fun release(
        delegationId: UUID,
        reservationId: UUID,
        callerPartyId: CallerPartyId,
    ): SpendReservation = settle(delegationId, reservationId, callerPartyId, SpendReservationState.RELEASED)

    /**
     * Confirm and release differ only in the target state, so they share the compare-and-set and
     * the replay rules. Re-asking for the state a reservation is already in is a no-op (the edge
     * retries both branches); asking for the OTHER terminal state is a conflict, because it would
     * either re-open a ceiling already spent through or consume one already given back.
     */
    private suspend fun settle(
        delegationId: UUID,
        reservationId: UUID,
        callerPartyId: CallerPartyId,
        target: SpendReservationState,
    ): SpendReservation {
        val grant = loadForGrantee(delegationId, callerPartyId)
        val now = OffsetDateTime.now(clock)
        val settled = reservationRepository.settle(delegationId, reservationId, target, now) { reservation ->
            settlementEvent(grant, reservation, target, now)
        }
        if (settled != null) return settled

        val current = reservationRepository.findById(delegationId, reservationId)
            ?: throw SpendReservationNotFoundException(reservationId)
        if (current.state == target) return current
        throw SpendReservationStateException(
            "reservation $reservationId is ${current.state} and cannot become $target",
        )
    }

    /**
     * ADR-0249 D4 — the confirmation and the release halves of "every grant, reservation,
     * confirmation and revocation is an audit event" (#5728).
     *
     * Built here rather than in the repository because the grantor/grantee pair belongs to the
     * grant, which the use case has already loaded and authorised against; the repository sees
     * only the reservation row.
     */
    private fun settlementEvent(
        grant: DelegationGrant,
        reservation: SpendReservation,
        target: SpendReservationState,
        now: OffsetDateTime,
    ): DomainEvent {
        val amount = EventMoney(reservation.amount.amount, reservation.amount.currency.code)
        val settledAt = reservation.settledAt ?: now
        return when (target) {
            SpendReservationState.CONFIRMED -> SpendConfirmed(
                aggregateId = grant.id,
                reservationId = reservation.id,
                grantorPartyId = grant.grantorPartyId,
                granteePartyId = grant.granteePartyId,
                amount = amount,
                settledAt = settledAt,
                occurredAt = now.toInstant(),
            )

            SpendReservationState.RELEASED -> SpendReleased(
                aggregateId = grant.id,
                reservationId = reservation.id,
                grantorPartyId = grant.grantorPartyId,
                granteePartyId = grant.granteePartyId,
                amount = amount,
                settledAt = settledAt,
                occurredAt = now.toInstant(),
            )

            // `settle` is only ever reached from confirm() or release(); RESERVED is not a target
            // the compare-and-set accepts, so this branch is unreachable rather than a state to
            // audit. Failing loudly beats inventing an event type for it.
            SpendReservationState.RESERVED -> error("RESERVED is not a settlement target")
        }
    }

    /**
     * The party who spends is the GRANTEE, so a customer-scoped caller must be that party. A
     * grantor cannot reserve on their own delegate's ceiling, and nobody else can reach the
     * reservation at all — the endpoint would otherwise be an oracle for how much of someone
     * else's limit is left. `null` = a bank/back-office call, gated by role and OPA (see
     * [CallerPartyId]).
     */
    private suspend fun loadForGrantee(delegationId: UUID, callerPartyId: CallerPartyId): DelegationGrant {
        val grant = delegationRepository.findById(delegationId) ?: throw DelegationNotFoundException(delegationId)
        if (callerPartyId != null && callerPartyId != grant.granteePartyId) {
            throw DelegationNotFoundException(delegationId)
        }
        return grant
    }
}
