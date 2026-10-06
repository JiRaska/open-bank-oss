// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardprocessing.application.port.`in`.CardDisputeUseCase
import com.openbank.cardprocessing.application.port.`in`.OpenDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.RefreshDisputeCommand
import com.openbank.cardprocessing.application.port.`in`.SubmitEvidenceCommand
import com.openbank.cardprocessing.application.port.out.CardAuthorizationRepository
import com.openbank.cardprocessing.application.port.out.CardDisputeCaseRepository
import com.openbank.cardprocessing.application.port.out.CardLifecycleMetricsPort
import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.application.port.out.LifecycleIdempotencyPort
import com.openbank.cardprocessing.application.port.out.LifecycleOperation
import com.openbank.cardprocessing.application.port.out.Reservation
import com.openbank.cardprocessing.application.port.out.UNATTRIBUTED_SCHEME
import com.openbank.cardprocessing.domain.event.CardDisputeEvidenceSubmitted
import com.openbank.cardprocessing.domain.event.CardDisputeOpened
import com.openbank.cardprocessing.domain.event.CardDisputeStatusChanged
import com.openbank.cardprocessing.domain.event.CardLifecycleEvent
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.DisputeEvidenceRecord
import com.openbank.cardprocessing.domain.model.DisputeOutcome
import com.openbank.cardprocessing.domain.model.DisputeRefusal
import com.openbank.cardprocessing.domain.model.DisputeStatus
import com.openbank.libs.domain.cards.scheme.DisputeEvidence
import com.openbank.libs.domain.cards.scheme.DisputePort
import com.openbank.libs.domain.cards.scheme.SchemeDispute
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRequestInProgressException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

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
 * The disputed currency must be the authorisation's (a chargeback is filed in the transaction
 * currency; two currencies have no order without a rate), and the amounts are compared as Money.
 *
 * ## Idempotency and closed cases
 *
 * Opening and evidence filing reserve the caller's `Idempotency-Key` in the database BEFORE the
 * network is asked ([LifecycleIdempotencyPort]): of two concurrent same-key requests exactly one
 * calls the network, the other replays its result or gets 409 IN_PROGRESS. A closed case is terminal
 * — [refreshStatus] never moves it, it only reports a disagreeing network.
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
@Suppress(
    // The count is inflated by small, named private steps (recordOpened / eligibility / refuse /
    // refuseOpen / bankStatusFor / outboxMessage). Splitting the class would put one flow — open,
    // file evidence, refresh — across two files for a metric, and a reader following a chargeback
    // would have to follow it across both.
    "TooManyFunctions",
)
class CardDisputeService(
    private val disputes: DisputePort,
    private val cases: CardDisputeCaseRepository,
    private val idempotency: LifecycleIdempotencyPort,
    private val authorizations: CardAuthorizationRepository,
    private val metrics: CardLifecycleMetricsPort,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : CardDisputeUseCase {

    private val log = Logger.getLogger(CardDisputeService::class.java)

    override suspend fun open(command: OpenDisputeCommand): DisputeOutcome {
        val claim = IdempotencyClaim(LifecycleOperation.DISPUTE_OPEN, command.idempotencyKey)
        when (val reservation = idempotency.reserve(claim.operation, claim.key, fingerprintOf(command))) {
            is Reservation.Completed -> return DisputeOutcome.Accepted(
                cases.findById(reservation.resultId)
                    ?: error("idempotency reservation points at missing dispute ${reservation.resultId}"),
            )
            Reservation.InProgress -> throw IdempotencyRequestInProgressException()
            Reservation.Mismatch -> throw IdempotencyKeyReusedException()
            Reservation.Claimed -> Unit
        }
        return holdingClaim(claim, "dispute open for authorisation ${command.authorizationId}") { network ->
            openClaimed(command, claim, network)
        }
    }

    private suspend fun openClaimed(
        command: OpenDisputeCommand,
        claim: IdempotencyClaim,
        network: NetworkCallMarker,
    ): DisputeOutcome {
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

        network.called = true
        return when (
            val answer = disputes.open(
                networkReference,
                command.reasonCode,
                command.amountMinorUnits,
                authorization.currencyCode,
            )
        ) {
            is SchemeResult.Answered -> recordOpened(authorization, answer, command.idempotencyKey, claim)

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

    /** Set once the network has been asked; after that a failure must leave the reservation PENDING. */
    private class NetworkCallMarker {
        var called = false
    }

    /**
     * Runs [work] while holding [claim]: a refusal releases it (nothing happened, a retry may run),
     * an acceptance has already completed it in the result's transaction, and a failure releases it
     * only if the network was never asked — after that the network may have acted, and a retry under
     * the same key must not reach it again.
     */
    private suspend fun holdingClaim(
        claim: IdempotencyClaim,
        what: String,
        work: suspend (NetworkCallMarker) -> DisputeOutcome,
    ): DisputeOutcome {
        val network = NetworkCallMarker()
        try {
            val outcome = work(network)
            if (outcome is DisputeOutcome.Refused) idempotency.release(claim)
            return outcome
        } catch (e: CancellationException) {
            if (!network.called) withContext(NonCancellable) { idempotency.release(claim) }
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (!network.called) {
                idempotency.release(claim)
            } else {
                log.errorf(
                    e,
                    "%s failed after the network was called; idempotency key left PENDING so a retry " +
                        "cannot repeat the network call — reconcile with the scheme",
                    what,
                )
            }
            throw e
        }
    }

    /**
     * Writes the case the network just opened, with its event, in one transaction.
     *
     * Split out of [open] for length only — the flow reads the same, and the fields all come from
     * the scheme's answer rather than from the request: the network's case id, its reason code, its
     * amount and its deadline are what the case IS.
     */
    private suspend fun recordOpened(
        authorization: CardAuthorization,
        answer: SchemeResult.Answered<SchemeDispute>,
        idempotencyKey: String,
        claim: IdempotencyClaim,
    ): DisputeOutcome {
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
            idempotencyKey,
            claim,
        )
        metrics.disputeOpened(answer.scheme.name, null)
        return DisputeOutcome.Accepted(saved)
    }

    /**
     * Files evidence, idempotently, into an APPEND-ONLY history.
     *
     * Each submission is a row in `card_dispute_evidence` and its own event; the case's
     * `evidenceReference` is only the latest. A retried request (same `Idempotency-Key`, same body)
     * replays the case without asking the network again; a second, different filing is a new row.
     */
    override suspend fun submitEvidence(command: SubmitEvidenceCommand): DisputeOutcome {
        val claim = IdempotencyClaim(LifecycleOperation.DISPUTE_EVIDENCE, command.idempotencyKey)
        when (val reservation = idempotency.reserve(claim.operation, claim.key, fingerprintOf(command))) {
            // The fingerprint binds the dispute id, so a completed reservation is for THIS case.
            is Reservation.Completed -> return DisputeOutcome.Accepted(
                cases.findById(command.disputeId)
                    ?: error("idempotency reservation for evidence on missing dispute ${command.disputeId}"),
            )
            Reservation.InProgress -> throw IdempotencyRequestInProgressException()
            Reservation.Mismatch -> throw IdempotencyKeyReusedException()
            Reservation.Claimed -> Unit
        }
        return holdingClaim(claim, "evidence for dispute ${command.disputeId}") { network ->
            submitEvidenceClaimed(command, claim, network)
        }
    }

    private suspend fun submitEvidenceClaimed(
        command: SubmitEvidenceCommand,
        claim: IdempotencyClaim,
        network: NetworkCallMarker,
    ): DisputeOutcome {
        val case = cases.findById(command.disputeId)
            ?: return refuse(DisputeRefusal.CASE_NOT_FOUND, "no dispute ${command.disputeId}")
        if (case.terminal) {
            metrics.disputeEvidenceSubmitted(DisputeRefusal.CASE_TERMINAL.name)
            return refuse(DisputeRefusal.CASE_TERMINAL, "case ${case.networkCaseId} is ${case.status}")
        }

        network.called = true
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
                val record = DisputeEvidenceRecord(
                    id = Ids.newId(),
                    disputeId = case.id,
                    documentReference = command.documentReference,
                    note = command.note,
                    schemeStatus = answer.value.status,
                    submittedAt = now,
                )
                val event = CardDisputeEvidenceSubmitted(
                    disputeId = updated.id,
                    authorizationId = updated.authorizationId,
                    cardId = updated.cardId,
                    networkCaseId = updated.networkCaseId,
                    documentReference = command.documentReference,
                    occurredAt = now,
                )
                val saved = cases.recordEvidence(
                    updated,
                    record,
                    outboxMessage(updated.id, CardDisputeEvidenceSubmitted.EVENT_TYPE, event),
                    claim,
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

    override suspend fun evidenceHistory(disputeId: UUID): List<DisputeEvidenceRecord>? =
        cases.findById(disputeId)?.let { cases.findEvidence(disputeId) }

    /**
     * Re-reads the network's status and response deadline and records a MOVE, publishing nothing
     * when neither value moved.
     *
     * Idempotent under the caller's `Idempotency-Key` like [open]: a retried refresh replays the case
     * the first one produced and never asks the network again. Whatever the outcome — a move, no move,
     * or a CLOSED case whose stored outcome is kept — a successful refresh completes the reservation
     * pointing at the case; a refusal releases it.
     */
    override suspend fun refreshStatus(command: RefreshDisputeCommand): DisputeOutcome {
        val claim = IdempotencyClaim(LifecycleOperation.DISPUTE_REFRESH, command.idempotencyKey)
        when (val reservation = idempotency.reserve(claim.operation, claim.key, fingerprintOf(command))) {
            is Reservation.Completed -> return DisputeOutcome.Accepted(
                cases.findById(reservation.resultId)
                    ?: error("idempotency reservation points at missing dispute ${reservation.resultId}"),
            )
            Reservation.InProgress -> throw IdempotencyRequestInProgressException()
            Reservation.Mismatch -> throw IdempotencyKeyReusedException()
            Reservation.Claimed -> Unit
        }
        return holdingClaim(claim, "refresh of dispute ${command.disputeId}") { network ->
            refreshClaimed(command.disputeId, claim, network)
        }
    }

    /**
     * Re-reads the network's status and records a MOVE, publishing nothing when nothing moved.
     *
     * An event per poll would make "the case changed" indistinguishable from "somebody looked at
     * it", and every consumer would have to de-duplicate a stream that is mostly repeats.
     *
     * A CLOSED case (WON, LOST, WITHDRAWN) is terminal and is never mutated here: its outcome has
     * already been announced, booked and possibly paid out, and a later network read that disagrees
     * is a discrepancy for a person to investigate, not a state change to apply silently. The stored
     * case is returned, and a disagreement is counted and logged.
     */
    private suspend fun refreshClaimed(
        disputeId: UUID,
        claim: IdempotencyClaim,
        network: NetworkCallMarker,
    ): DisputeOutcome {
        val case = cases.findById(disputeId)
            ?: return refuse(DisputeRefusal.CASE_NOT_FOUND, "no dispute $disputeId")
        network.called = true
        if (case.terminal) return refreshClosed(case).also { idempotency.complete(claim, case.id) }

        return when (val answer = disputes.status(case.networkCaseId)) {
            is SchemeResult.Answered -> {
                val bankStatus = bankStatusFor(answer.value, case.status)
                if (
                    answer.value.status == case.schemeStatus &&
                    bankStatus == case.status &&
                    answer.value.respondByDate == case.respondByDate
                ) {
                    idempotency.complete(claim, case.id)
                    return DisputeOutcome.Accepted(case)
                }
                val now = Instant.now(clock)
                val updated = case.copy(
                    status = bankStatus,
                    schemeStatus = answer.value.status,
                    respondByDate = answer.value.respondByDate,
                    updatedAt = now,
                )
                val event = CardDisputeStatusChanged(
                    disputeId = updated.id,
                    authorizationId = updated.authorizationId,
                    cardId = updated.cardId,
                    networkCaseId = updated.networkCaseId,
                    previousStatus = case.status.name,
                    status = updated.status.name,
                    schemeStatus = updated.schemeStatus,
                    occurredAt = now,
                    respondByDate = updated.respondByDate,
                )
                DisputeOutcome.Accepted(
                    cases.save(
                        updated,
                        outboxMessage(updated.id, CardDisputeStatusChanged.EVENT_TYPE, event),
                        idempotencyKeyOf(updated),
                        claim,
                    ),
                )
            }

            is SchemeResult.Unanswered -> refuse(refusalFor(answer.failure), answer.detail)
        }
    }

    /**
     * The network is still asked — a disagreement about a closed case is worth knowing — but its
     * answer is only compared, never applied. An unreachable network changes nothing either.
     */
    private suspend fun refreshClosed(case: CardDisputeCase): DisputeOutcome {
        val answer = disputes.status(case.networkCaseId)
        if (answer is SchemeResult.Answered) {
            val reported = bankStatusFor(answer.value, case.status)
            if (reported != case.status) {
                metrics.disputeTerminalMismatch(answer.scheme.name, case.status.name, reported.name)
                log.warnf(
                    "closed dispute %s (network case %s) is stored %s but the network now reports %s (%s); " +
                        "the stored outcome is kept — investigate",
                    case.id,
                    case.networkCaseId,
                    case.status,
                    reported,
                    answer.value.status,
                )
            }
        }
        return DisputeOutcome.Accepted(case)
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
        // An unknown or malformed code is the CLIENT's error: InvalidMoneyException, which libs-runtime
        // renders as a 400. Only a well-formed code that differs from the authorisation's is a 409.
        val requested = CurrencyCode.of(command.currencyCode)
        val authorised = CurrencyCode.of(authorization.currencyCode)
        if (requested != authorised) {
            return refuseOpen(
                DisputeRefusal.CURRENCY_MISMATCH,
                "the dispute is in $requested but authorisation ${authorization.id} cleared in $authorised; " +
                    "a chargeback is filed in the transaction currency, never converted",
            )
        }
        // Compared as Money, not as raw longs: two minor-unit counts are only comparable once both are
        // known to be in one currency, and Money refuses the comparison otherwise.
        val disputed = moneyOf(command.amountMinorUnits, requested)
        val cleared = moneyOf(authorization.clearedAmountMinorUnits, authorised)
        if (!disputed.isPositive() || disputed > cleared) {
            return refuseOpen(
                DisputeRefusal.AMOUNT_EXCEEDS_CLEARED,
                "the disputed amount must be positive and at most the cleared $cleared",
            )
        }
        return null
    }

    private fun moneyOf(minorUnits: Long, currency: CurrencyCode): Money =
        Money(BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits), currency)

    private fun fingerprintOf(command: OpenDisputeCommand): String = RequestFingerprint.of(
        "POST",
        "/api/v1/card-disputes",
        listOf(command.authorizationId, command.reasonCode, command.amountMinorUnits, command.currencyCode.uppercase())
            .joinToString("\n"),
    )

    private fun fingerprintOf(command: RefreshDisputeCommand): String =
        RequestFingerprint.of("POST", "/api/v1/card-disputes/${command.disputeId}/refresh", "")

    private fun fingerprintOf(command: SubmitEvidenceCommand): String = RequestFingerprint.of(
        "POST",
        "/api/v1/card-disputes/${command.disputeId}/evidence",
        listOf(command.documentReference, command.note.orEmpty()).joinToString("\n"),
    )

    private fun refuse(reason: DisputeRefusal, detail: String?): DisputeOutcome = DisputeOutcome.Refused(reason, detail)

    /**
     * A refusal on the OPEN path, counted before it is returned.
     *
     * The scheme is [UNATTRIBUTED_SCHEME] because these refusals happen before any network call — the
     * request never reached a scheme, and labelling them with the configured binding would make the
     * dashboard blame a network that was never asked. That distinction is the difference between
     * "operators keep trying to dispute holds" and "the scheme is down".
     */
    private fun refuseOpen(reason: DisputeRefusal, detail: String?): DisputeOutcome {
        metrics.disputeOpened(UNATTRIBUTED_SCHEME, reason.name)
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
}
