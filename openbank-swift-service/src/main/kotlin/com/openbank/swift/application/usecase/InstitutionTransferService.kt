// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.out.InstitutionTransferMetrics
import com.openbank.swift.application.port.out.InstitutionTransferRejection
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.InstitutionTransferSubmission
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.NonConformingMessageException
import com.openbank.swift.application.port.out.ReconciliationCause
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SchemeStatus
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.InstitutionTransferStatus
import com.openbank.swift.domain.institution.InstitutionTransferStatusChanged
import com.openbank.swift.domain.institution.Mt202Mapper
import com.openbank.swift.domain.institution.ReleaseValidation
import com.openbank.swift.domain.institution.ReleasedPayment
import com.openbank.swift.domain.institution.ReleasedPaymentValidator
import com.openbank.swift.domain.institution.ValidatedRelease
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * A release this rail refuses. Deterministic — a redelivery fails identically — so the consumer
 * lets it reach the channel's dead-letter queue without retrying. Carries no payload value.
 */
class InstitutionTransferRefusedException(message: String) : IllegalArgumentException(message)

/**
 * The bank-own (financial-institution) credit-transfer rail (contract 9.3): consumes treasury's
 * `treasury.payment.released.v1`, builds and submits a `pacs.009`, and announces every lifecycle
 * transition as `swift.institution-transfer.status.v1` through swift's transactional outbox.
 *
 * **Idempotent on `idempotencyKey`** (natural key, unique constraint). A replay of a release that
 * already has a row re-emits nothing: a terminal or SENT transfer is returned as it is; one still
 * ACCEPTED (the scheme was unreachable last time) resumes submission, which emits the transitions
 * that never happened rather than repeating any that did.
 *
 * **Every transition is one transaction with its outbox rows.** ACCEPTED is written with the row
 * insert; an `ACSC` writes SETTLED with both SENT and SETTLED events (in that order, the outbox
 * claims one aggregate's rows oldest first); `ACSP`/`RCVD` writes SENT; `RJCT` writes REJECTED.
 *
 * **Invalid release.** Validation failures throw [InstitutionTransferRefusedException] for the
 * dead-letter queue — never a silent ack — after recording a REJECTED event once per readable
 * payment id, so treasury learns of it without waiting for an operator to read the DLQ.
 */
// One small function per lifecycle edge (release, replay, submit, resolve, resolveDue, send, apply,
// transition, manual, unknown, refuse): merging them would hide the branches each test pins.
@Suppress("TooManyFunctions")
@ApplicationScoped
class InstitutionTransferService(
    private val repo: InstitutionTransferRepository,
    private val gateway: SchemeGatewayPort,
    private val metrics: InstitutionTransferMetrics,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.swift.institution-transfer.value-date-past-tolerance-days", defaultValue = "1")
    private val pastToleranceDays: Long,
) {
    private val log = Logger.getLogger(InstitutionTransferService::class.java)

    suspend fun onReleased(payment: ReleasedPayment): InstitutionTransfer {
        // Idempotency FIRST: a replay of a payment this rail already holds is answered from the
        // stored transfer and never re-judged — today's date would refuse a release whose value
        // date has since passed, announcing REJECTED for a payment that may already be SETTLED.
        val paymentId = payment.paymentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val known = payment.idempotencyKey?.let { repo.findByIdempotencyKey(it) }
            ?: paymentId?.let { repo.findByPaymentId(it) }
        if (known != null) return replay(known, payment)

        val today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val release = when (val v = ReleasedPaymentValidator.validate(payment, today, pastToleranceDays)) {
            is ReleaseValidation.Valid -> v.release
            is ReleaseValidation.Invalid -> refuse(payment, v.rejection.reasonCode, v.rejection.reason)
        }
        val fresh = newTransferAt(release, Instant.now(clock))
        // The claim is taken in the SAME transaction as the row and its ACCEPTED event. Losing it
        // means a rejection (or a concurrent identical release) already owns this payment: then
        // nothing was written, and the record is dead-lettered without any status event.
        if (!repo.insertClaimed(fresh, listOf(objectMapper.statusEvent(fresh, fresh.createdAt)))) {
            log.warnf("Payment %s is already claimed by another outcome; dead-lettering", release.paymentId)
            throw InstitutionTransferRefusedException("paymentId or idempotencyKey already claimed")
        }
        return submit(repo.findByPaymentId(fresh.paymentId) ?: fresh)
    }

    private suspend fun replay(existing: InstitutionTransfer, payment: ReleasedPayment): InstitutionTransfer {
        if (existing.paymentId.toString() != payment.paymentId || existing.idempotencyKey != payment.idempotencyKey) {
            // Same key, different payment (or the reverse): never a second message, never a silent merge.
            log.errorf(
                "Release for payment %s conflicts with stored transfer %s; dead-lettering",
                payment.paymentId,
                existing.id,
            )
            throw InstitutionTransferRefusedException("idempotencyKey and paymentId do not match the stored transfer")
        }
        return when (existing.status) {
            InstitutionTransferStatus.ACCEPTED -> submit(existing)
            InstitutionTransferStatus.SUBMITTING, InstitutionTransferStatus.SUBMISSION_UNKNOWN -> resolve(existing)
            else -> existing
        }
    }

    /**
     * The resolution sweeper's entry for one due transfer (unknown submission or SENT past the
     * configured age). Unknown states take the same path as a redelivered release ([resolve]):
     * status query by UETR, single-worker resend claim, MANUAL_RECONCILIATION on ambiguity. A SENT
     * transfer is asked about too: ACSC settles it (announcing SETTLED only — SENT already went
     * out), RJCT rejects it, ACSP leaves it; a scheme that does not know a transfer it already
     * accepted, or any ambiguous answer, stops it for a human. Returns the resulting state.
     */
    suspend fun resolveDue(transfer: InstitutionTransfer): InstitutionTransfer = when (transfer.status) {
        InstitutionTransferStatus.SUBMITTING, InstitutionTransferStatus.SUBMISSION_UNKNOWN -> resolve(transfer)
        InstitutionTransferStatus.SENT -> when (val status = gateway.queryInstitutionTransferStatus(transfer)) {
            is SchemeStatus.Known ->
                if (status.submission.verdict ==
                    InstitutionTransferVerdict.IN_PROCESS
                ) {
                    transfer
                } else {
                    apply(transfer, status.submission)
                }
            is SchemeStatus.Ambiguous -> manual(transfer, causeOf(status))
            SchemeStatus.NotKnown -> manual(transfer, ReconciliationCause.SENT_UNKNOWN_TO_SCHEME)
        }
        else -> transfer
    }

    /**
     * First submission. The message identity (MsgId, CreDtTm; the UETR is fixed at creation) is
     * committed as SUBMITTING BEFORE the scheme is called, so any later resend is byte-identical.
     * One call, no automatic retry: no readable answer moves the transfer to SUBMISSION_UNKNOWN
     * and the failure propagates for redelivery, which resolves it by status query ([resolve]).
     */
    private suspend fun submit(accepted: InstitutionTransfer): InstitutionTransfer {
        val now = maxOf(Instant.now(clock), accepted.updatedAt.plusMillis(1))
        val prepared = repo.saveWithOutbox(
            accepted.copy(
                status = InstitutionTransferStatus.SUBMITTING,
                messageId = accepted.messageId ?: InstitutionTransfer.messageIdFor(accepted),
                messageCreatedAt = accepted.messageCreatedAt ?: now,
                updatedAt = now,
            ),
            emptyList(),
        )
        return send(prepared)
    }

    /**
     * SUBMITTING / SUBMISSION_UNKNOWN. Only an unambiguous answer is acted on: a status naming
     * this MsgId + UETR is applied; the scheme's TYPED "unknown UETR" licenses ONE resend, taken
     * under an optimistic version so only one worker can send it. Anything else — an untyped 404,
     * a report about another message, still unknown after the resend — stops
     * in MANUAL_RECONCILIATION: counted, alerted, never resent.
     */
    private suspend fun resolve(transfer: InstitutionTransfer): InstitutionTransfer {
        val status = try {
            gateway.queryInstitutionTransferStatus(transfer)
        } catch (e: SchemeGatewayUnavailableException) {
            markUnknown(transfer)
            throw e
        }
        return when (status) {
            is SchemeStatus.Known -> apply(transfer, status.submission)
            is SchemeStatus.Ambiguous -> manual(transfer, causeOf(status))
            SchemeStatus.NotKnown -> when {
                transfer.resendTaken -> manual(transfer, ReconciliationCause.UNKNOWN_AFTER_RESEND)
                // Lost the claim: another worker holds the one resend and owns this transfer now.
                // Do nothing — marking it here would overwrite the winner (optimistic version).
                !repo.takeResendMarker(transfer) -> repo.findByPaymentId(transfer.paymentId) ?: transfer
                else -> send(repo.findByPaymentId(transfer.paymentId) ?: transfer.copy(resendTaken = true))
            }
        }
    }

    private suspend fun manual(t: InstitutionTransfer, cause: ReconciliationCause): InstitutionTransfer {
        log.errorf("Bank-own transfer %s needs manual reconciliation (%s); nothing resent", t.paymentId, cause)
        metrics.manualReconciliation(t.rail, cause)
        val now = maxOf(Instant.now(clock), t.updatedAt.plusMillis(1))
        return repo.saveWithOutbox(
            t.copy(status = InstitutionTransferStatus.MANUAL_RECONCILIATION, updatedAt = now),
            emptyList(),
        )
    }

    private suspend fun send(prepared: InstitutionTransfer): InstitutionTransfer {
        val submission = try {
            gateway.submitInstitutionTransfer(prepared)
        } catch (e: NonConformingMessageException) {
            log.errorf(e, "Rail built a non-conforming pacs.009 for payment %s; rejecting", prepared.paymentId)
            return transition(
                prepared,
                InstitutionTransferStatus.REJECTED,
                "FF01",
                "rail built a non-conforming pacs.009",
                null,
            )
        } catch (e: SchemeGatewayUnavailableException) {
            markUnknown(prepared)
            throw e
        }
        return apply(prepared, submission)
    }

    private suspend fun markUnknown(t: InstitutionTransfer) {
        val current = repo.findByPaymentId(t.paymentId) ?: t
        if (current.status == InstitutionTransferStatus.SUBMISSION_UNKNOWN) return
        val now = maxOf(Instant.now(clock), current.updatedAt.plusMillis(1))
        repo.saveWithOutbox(
            current.copy(status = InstitutionTransferStatus.SUBMISSION_UNKNOWN, updatedAt = now),
            emptyList(),
        )
    }

    private suspend fun apply(
        transfer: InstitutionTransfer,
        submission: InstitutionTransferSubmission,
    ): InstitutionTransfer {
        val raw = submission.rawPacs009.ifBlank { null }
        return when (submission.verdict) {
            InstitutionTransferVerdict.IN_PROCESS -> transition(
                transfer,
                InstitutionTransferStatus.SENT,
                null,
                null,
                raw,
            )
            InstitutionTransferVerdict.SETTLED -> transition(
                transfer,
                InstitutionTransferStatus.SETTLED,
                null,
                null,
                raw,
            )
            InstitutionTransferVerdict.REJECTED -> transition(
                transfer,
                InstitutionTransferStatus.REJECTED,
                submission.reasonCode ?: "NARR",
                "scheme reject (pacs.002)",
                raw,
            )
        }
    }

    /** One transaction: the new state plus every event it implies (SETTLED implies SENT first). */
    private suspend fun transition(
        transfer: InstitutionTransfer,
        status: InstitutionTransferStatus,
        reasonCode: String?,
        reason: String?,
        rawPacs009: String?,
    ): InstitutionTransfer {
        // Never at or before the previous transition, so the outbox's oldest-first claim of one
        // aggregate's rows replays the lifecycle in order even under a coarse or frozen clock.
        val now = maxOf(Instant.now(clock), transfer.updatedAt.plusMillis(1))
        val updated = transfer.copy(
            status = status,
            reasonCode = reasonCode,
            reason = reason,
            rawPacs009 = rawPacs009 ?: transfer.rawPacs009,
            updatedAt = now,
        )
        val events = buildList {
            // SETTLED implies SENT — unless SENT was already announced (deferred settlement).
            if (status == InstitutionTransferStatus.SETTLED && transfer.status != InstitutionTransferStatus.SENT) {
                add(objectMapper.statusEvent(updated.copy(status = InstitutionTransferStatus.SENT), now))
            }
            // Strictly later than the SENT row, so the outbox's oldest-first claim keeps the order.
            add(objectMapper.statusEvent(updated, if (isEmpty()) now else now.plusMillis(1)))
        }
        return repo.saveWithOutbox(updated, events)
    }

    private suspend fun refuse(payment: ReleasedPayment, reasonCode: String, reason: String): Nothing {
        val paymentId = payment.paymentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (paymentId != null) {
            val now = Instant.now(clock)
            val synthetic = payment.synthetic ?: false
            val key = payment.idempotencyKey?.take(ReleasedPaymentValidator.MAX_IDEMPOTENCY_KEY)
            val payload = InstitutionTransferStatusChanged(
                paymentId = paymentId.toString(),
                idempotencyKey = key,
                status = InstitutionTransferStatus.REJECTED.name,
                messageType = InstitutionTransfer.MESSAGE_TYPE,
                schemeReference = null,
                reasonCode = reasonCode,
                reason = reason,
                at = now.toString(),
                synthetic = synthetic,
                ssiVersion = payment.ssiVersion,
            )
            val written = repo.recordRejectionOnce(
                InstitutionTransferRejection(paymentId, key, reasonCode, reason, synthetic, now),
                objectMapper.statusOutbox(paymentId, payload, now, synthetic),
            )
            log.warnf(
                "Refused release for payment %s (%s: %s); REJECTED %s",
                paymentId,
                reasonCode,
                reason,
                if (written) "emitted" else "already emitted",
            )
        } else {
            log.warnf("Refused release with no readable paymentId (%s: %s)", reasonCode, reason)
        }
        throw InstitutionTransferRefusedException("$reasonCode: $reason")
    }
}

private fun newTransferAt(r: ValidatedRelease, now: Instant): InstitutionTransfer {
    val transfer = InstitutionTransfer(
        id = r.paymentId,
        paymentId = r.paymentId,
        idempotencyKey = r.idempotencyKey,
        rail = r.rail,
        amount = r.amount,
        valueDate = r.valueDate,
        debtor = r.debtor,
        debtorGlAccountingClass = r.debtorGlAccountingClass,
        creditor = r.creditor,
        creditorName = r.creditorName,
        intermediaryBic = r.intermediaryBic,
        remittanceInformation = r.remittanceInformation,
        dealIds = r.dealIds,
        flowIds = r.flowIds,
        releasedBy = r.releasedBy,
        releasedAt = r.releasedAt,
        synthetic = r.synthetic,
        status = InstitutionTransferStatus.ACCEPTED,
        uetr = InstitutionTransfer.uetrFor(r.paymentId),
        reasonCode = null,
        reason = null,
        rawPacs009 = null,
        rawMt202 = null,
        createdAt = now,
        updatedAt = now,
        ssiVersion = r.ssiVersion,
    )
    return transfer.copy(rawMt202 = Mt202Mapper.render(transfer))
}

private fun ObjectMapper.statusEvent(t: InstitutionTransfer, at: Instant): OutboxMessage {
    check(t.status in InstitutionTransferStatus.ANNOUNCED) { "internal status ${t.status} is never announced" }
    val payload = InstitutionTransferStatusChanged(
        paymentId = t.paymentId.toString(),
        idempotencyKey = t.idempotencyKey,
        status = t.status.name,
        messageType = InstitutionTransfer.MESSAGE_TYPE,
        schemeReference = t.uetr,
        reasonCode = t.reasonCode,
        reason = t.reason,
        at = at.toString(),
        synthetic = t.synthetic,
        ssiVersion = t.ssiVersion,
    )
    return statusOutbox(t.paymentId, payload, at, t.synthetic)
}

private fun ObjectMapper.statusOutbox(
    paymentId: UUID,
    payload: InstitutionTransferStatusChanged,
    at: Instant,
    synthetic: Boolean,
) = OutboxMessage(
    aggregateId = paymentId,
    eventType = InstitutionTransferStatusChanged.EVENT_TYPE,
    payload = writeValueAsString(payload),
    createdAt = at,
    synthetic = synthetic,
)

private fun causeOf(a: SchemeStatus.Ambiguous) = if (a.why ==
    SchemeStatus.UNTYPED_NOT_FOUND
) {
    ReconciliationCause.UNTYPED_NOT_FOUND
} else {
    ReconciliationCause.MISMATCHED_STATUS
}
