// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardprocessing.application.port.`in`.CardDisputeUseCase
import com.openbank.cardprocessing.application.port.`in`.OpenDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.SubmitEvidenceCommand
import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardDisputeCaseRepository
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.domain.event.CardDisputeEvidenceSubmitted
import com.openbank.cardprocessing.domain.event.CardDisputeOpened
import com.openbank.cardprocessing.domain.event.CardDisputeStatusChanged
import com.openbank.cardprocessing.domain.event.CardLifecycleEvent
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.DisputeOutcome
import com.openbank.cardprocessing.domain.model.DisputeRefusal
import com.openbank.cardprocessing.domain.model.DisputeStatus
import com.openbank.libs.domain.cards.scheme.DisputeEvidence
import com.openbank.libs.domain.cards.scheme.DisputePort
import com.openbank.libs.domain.cards.scheme.SchemeDispute
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Chargeback cases against cleared card spend — the caller for
 * [DisputePort][com.openbank.libs.domain.cards.scheme.DisputePort].
 *
 * ## Opening fails CLOSED, and that is the whole design
 *
 * A case is written here only after the network assigned it an id. The tempting alternative — record
 * the intent locally and reconcile later — produces a row with a `respondByDate` nobody is counting
 * down: it renders on the disputes desk as an active case while the representment window expires in
 * silence, and the loss is only discovered when the money is gone. The bank would rather be told the
 * network is unreachable.
 *
 * ## What may be disputed
 *
 * Only money that actually moved. An authorisation that is still holding funds has nothing to charge
 * back — the correct instrument is a reversal, which the money path already has — and the disputed
 * amount may never exceed what cleared. Both are checked against the authorisation row, not against
 * a number the caller supplied.
 *
 * One live case per authorisation. Checked here for the message and enforced by a partial UNIQUE
 * index in the database, because a check in application code alone is a race between two operators
 * pressing the button at once, not a constraint.
 *
 * ## Its relationship to openbank-dispute-service, stated because the two are easy to confuse
 *
 * `openbank-dispute-service` owns the CUSTOMER's case (ADR-0117): who complained, what evidence was
 * gathered, which remediation the investigation supports. This service owns the NETWORK's case: the
 * scheme's own id, its reason code, its respond-by date. They are different objects about the same
 * money and neither is a copy of the other.
 *
 * What is NOT wired, measured on `origin/main` 2026-09-05: `DisputeResolution.CHARGEBACK` in
 * dispute-service is a label with nothing behind it — that service holds no `networkCaseId`, calls
 * no scheme, and its domain model has no field for one. So a case can be resolved as "chargeback"
 * without a chargeback ever being filed with Visa or Mastercard, which is the same shape as the
 * defect ADR-0283 was written about: a decision recorded and never carried out.
 *
 * The join is deliberately NOT modelled here yet. A `bankCaseReference` column that nothing writes
 * would be a field no code path consumes — a latent trap, not a link — so the wiring is tracked as
 * its own issue rather than half-built.
 */
@ApplicationScoped
class CardDisputeService(
    private val disputes: DisputePort,
    private val cases: CardDisputeCaseRepository,
    private val authorizations: CardAuthorizationRepository,
    private val metrics: CardLifecycleMetricsPort,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : CardDisputeUseCase {

    private val log = Logger.getLogger(CardDisputeService::class.java)

    override suspend fun open(command: OpenDisputeCommand): DisputeOutcome {
        cases.findByIdempotencyKey(command.idempotencyKey)?.let { return DisputeOutcome.Accepted(it) }

        val authorization = authorizations.findById(command.authorizationId)
            ?: return refuseOpen(
                DisputeRefusal.AUTHORIZATION_NOT_FOUND,
                "no authorisation ${command.authorizationId}",
            )

        eligibility(authorization, command)?.let { return it }
        cases.findLiveByAuthorization(authorization.id)?.let {
            return refuseOpen(
                DisputeRefusal.ALREADY_DISPUTED,
                "case ${it.networkCaseId} is already ${it.status} against this authorisation",
            )
        }
        val networkReference = authorization.networkReference
            ?: return refuseOpen(
                DisputeRefusal.NO_NETWORK_REFERENCE,
                "the authorisation carries no acquirer reference, so the network cannot be told which " +
                    "transaction is disputed",
            )

        return when (
            val answer = disputes.open(
                networkReference,
                command.reasonCode,
                command.amountMinorUnits,
                command.currencyCode,
            )
        ) {
            is SchemeResult.Answered -> {
                val now = Instant.now(clock)
                val case = CardDisputeCase(
                    // UUIDv7 (ADR-0106) — a durable, indexed primary key.
                    id = Ids.newId(),
                    authorizationId = authorization.id,
                    cardId = authorization.cardId,
                    networkCaseId = answer.value.networkCaseId,
                    reasonCode = answer.value.reasonCode,
                    amountMinorUnits = answer.value.amountMinorUnits,
                    currencyCode = answer.value.currencyCode,
                    status = DisputeStatus.OPEN,
                    scheme = answer.scheme,
                    schemeStatus = answer.value.status,
                    respondByDate = answer.value.respondByDate,
                    evidenceReference = null,
                    openedAt = now,
                    updatedAt = now,
                )
                val event = CardDisputeOpened(
                    disputeId = case.id,
                    authorizationId = case.authorizationId,
                    cardId = case.cardId,
                    networkCaseId = case.networkCaseId,
                    reasonCode = case.reasonCode,
                    amountMinorUnits = case.amountMinorUnits,
                    currencyCode = case.currencyCode,
                    respondByDate = case.respondByDate,
                    scheme = case.scheme.name,
                    occurredAt = now,
                )
                val saved = cases.save(
                    case,
                    outboxMessage(case.id, CardDisputeOpened.EVENT_TYPE, event),
                    command.idempotencyKey,
                )
                metrics.disputeOpened(answer.scheme.name, null)
                DisputeOutcome.Accepted(saved)
            }

            is SchemeResult.Unanswered -> {
                val reason = refusalFor(answer.failure)
                metrics.disputeOpened(answer.scheme.name, reason.name)
                log.infof(
                    "dispute refused for authorisation %s: %s (%s) — %s",
                    authorization.id,
                    answer.failure,
                    answer.scheme,
                    answer.detail,
                )
                DisputeOutcome.Refused(reason, answer.detail)
            }
        }
    }

    override suspend fun submitEvidence(command: SubmitEvidenceCommand): DisputeOutcome {
        val case = cases.findById(command.disputeId)
            ?: return refuse(DisputeRefusal.CASE_NOT_FOUND, "no dispute ${command.disputeId}")
        if (case.terminal) {
            metrics.disputeEvidenceSubmitted(DisputeRefusal.CASE_TERMINAL.name)
            return refuse(DisputeRefusal.CASE_TERMINAL, "case ${case.networkCaseId} is ${case.status}")
        }

        val evidence = DisputeEvidence(case.networkCaseId, command.documentReference, command.note)
        return when (val answer = disputes.submitEvidence(evidence)) {
            is SchemeResult.Answered -> {
                val now = Instant.now(clock)
                val updated = case.copy(
                    status = DisputeStatus.EVIDENCE_SUBMITTED,
                    schemeStatus = answer.value.status,
                    evidenceReference = command.documentReference,
                    updatedAt = now,
                )
                val event = CardDisputeEvidenceSubmitted(
                    disputeId = updated.id,
                    authorizationId = updated.authorizationId,
                    cardId = updated.cardId,
                    networkCaseId = updated.networkCaseId,
                    documentReference = command.documentReference,
                    occurredAt = now,
                )
                val saved = cases.save(
                    updated,
                    outboxMessage(updated.id, CardDisputeEvidenceSubmitted.EVENT_TYPE, event),
                    idempotencyKeyOf(updated),
                )
                metrics.disputeEvidenceSubmitted(null)
                DisputeOutcome.Accepted(saved)
            }

            is SchemeResult.Unanswered -> {
                val reason = refusalFor(answer.failure)
                metrics.disputeEvidenceSubmitted(reason.name)
                refuse(reason, answer.detail)
            }
        }
    }

    /**
     * Re-reads the network's status and records a MOVE, publishing nothing when nothing moved.
     *
     * An event per poll would make "the case changed" indistinguishable from "somebody looked at
     * it", and every consumer would have to de-duplicate a stream that is mostly repeats.
     */
    override suspend fun refreshStatus(disputeId: UUID): DisputeOutcome {
        val case = cases.findById(disputeId)
            ?: return refuse(DisputeRefusal.CASE_NOT_FOUND, "no dispute $disputeId")

        return when (val answer = disputes.status(case.networkCaseId)) {
            is SchemeResult.Answered -> {
                val bankStatus = bankStatusFor(answer.value, case.status)
                if (answer.value.status == case.schemeStatus && bankStatus == case.status) {
                    return DisputeOutcome.Accepted(case)
                }
                val now = Instant.now(clock)
                val updated = case.copy(status = bankStatus, schemeStatus = answer.value.status, updatedAt = now)
                val event = CardDisputeStatusChanged(
                    disputeId = updated.id,
                    authorizationId = updated.authorizationId,
                    cardId = updated.cardId,
                    networkCaseId = updated.networkCaseId,
                    previousStatus = case.status.name,
                    status = updated.status.name,
                    schemeStatus = updated.schemeStatus,
                    occurredAt = now,
                )
                DisputeOutcome.Accepted(
                    cases.save(
                        updated,
                        outboxMessage(updated.id, CardDisputeStatusChanged.EVENT_TYPE, event),
                        idempotencyKeyOf(updated),
                    ),
                )
            }

            is SchemeResult.Unanswered -> refuse(refusalFor(answer.failure), answer.detail)
        }
    }

    override suspend fun findById(id: UUID): CardDisputeCase? = cases.findById(id)

    override suspend fun findByCard(cardId: UUID, limit: Int): List<CardDisputeCase> = cases.findByCardId(cardId, limit)

    /**
     * The bank's lifecycle read off the network's string, and only where the mapping is unambiguous.
     *
     * The network's vocabulary differs per scheme and moves with their releases, so anything not
     * recognised leaves the bank status ALONE rather than guessing — an unrecognised scheme status
     * still travels on [CardDisputeCase.schemeStatus], where an operator can read it verbatim. The
     * opposite design, mapping everything through a best guess, is how the two vocabularies end up
     * disagreeing in the place a deadline is computed.
     */
    private fun bankStatusFor(dispute: SchemeDispute, current: DisputeStatus): DisputeStatus =
        when (dispute.status.uppercase()) {
            "WON", "RESOLVED_WON", "REPRESENTED_WON" -> DisputeStatus.WON
            "LOST", "RESOLVED_LOST", "CHARGEBACK_ACCEPTED" -> DisputeStatus.LOST
            "WITHDRAWN", "CANCELLED" -> DisputeStatus.WITHDRAWN
            else -> current
        }

    /**
     * What may be disputed, checked against the authorisation ROW.
     *
     * Returns the refusal, or null when the request is eligible. Every branch counts a metric with
     * its reason: a disputes desk whose failures are invisible cannot tell "operators keep trying to
     * dispute holds" from "the scheme is down".
     */
    private fun eligibility(authorization: CardAuthorization, command: OpenDisputeCommand): DisputeOutcome? {
        if (authorization.clearedAmountMinorUnits <= 0L) {
            return refuseOpen(
                DisputeRefusal.NOTHING_CLEARED,
                "nothing has cleared on this authorisation — a hold is released with a reversal, not disputed",
            )
        }
        if (command.amountMinorUnits <= 0L || command.amountMinorUnits > authorization.clearedAmountMinorUnits) {
            return refuseOpen(
                DisputeRefusal.AMOUNT_EXCEEDS_CLEARED,
                "the disputed amount must be positive and at most the cleared " +
                    "${authorization.clearedAmountMinorUnits} minor units",
            )
        }
        return null
    }

    private fun refuse(reason: DisputeRefusal, detail: String?): DisputeOutcome =
        DisputeOutcome.Refused(reason, detail)

    /**
     * A refusal on the OPEN path, counted before it is returned.
     *
     * The scheme is [UNATTRIBUTED] because these refusals happen before any network call — the
     * request never reached a scheme, and labelling them with the configured binding would make the
     * dashboard blame a network that was never asked. That distinction is the difference between
     * "operators keep trying to dispute holds" and "the scheme is down".
     */
    private fun refuseOpen(reason: DisputeRefusal, detail: String?): DisputeOutcome {
        metrics.disputeOpened(UNATTRIBUTED, reason.name)
        return DisputeOutcome.Refused(reason, detail)
    }

    private fun idempotencyKeyOf(case: CardDisputeCase) = "dispute:${case.networkCaseId}"

    private fun refusalFor(failure: SchemeFailure): DisputeRefusal = when (failure) {
        SchemeFailure.NOT_BOUND, SchemeFailure.UNAVAILABLE, SchemeFailure.UNAUTHENTICATED ->
            DisputeRefusal.SCHEME_UNAVAILABLE
        SchemeFailure.NOT_FOUND -> DisputeRefusal.CASE_NOT_FOUND
        SchemeFailure.MALFORMED -> DisputeRefusal.SCHEME_REFUSED
    }

    private fun outboxMessage(aggregateId: UUID, eventType: String, event: CardLifecycleEvent): OutboxMessage =
        OutboxMessage(
            aggregateId = aggregateId,
            eventType = eventType,
            payload = mapper.writeValueAsString(event),
            createdAt = Instant.now(clock),
        )

    private companion object {
        /** No network was asked, so no network is named. */
        const val UNATTRIBUTED = "NONE"
    }
}
