// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.out.InstitutionTransferRejection
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.NonConformingMessageException
import com.openbank.swift.application.port.out.SchemeGatewayPort
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
@ApplicationScoped
class InstitutionTransferService(
    private val repo: InstitutionTransferRepository,
    private val gateway: SchemeGatewayPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.swift.institution-transfer.value-date-past-tolerance-days", defaultValue = "1")
    private val pastToleranceDays: Long,
) {
    private val log = Logger.getLogger(InstitutionTransferService::class.java)

    suspend fun onReleased(payment: ReleasedPayment): InstitutionTransfer {
        val today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val release = when (val v = ReleasedPaymentValidator.validate(payment, today, pastToleranceDays)) {
            is ReleaseValidation.Valid -> v.release
            is ReleaseValidation.Invalid -> refuse(payment, v.rejection.reasonCode, v.rejection.reason)
        }
        val existing = repo.findByIdempotencyKey(release.idempotencyKey)
            ?: repo.findByPaymentId(release.paymentId)
        if (existing != null) return replay(existing, release)

        val fresh = newTransfer(release)
        val accepted = repo.saveWithOutbox(fresh, listOf(event(fresh, fresh.createdAt)))
        return submit(accepted)
    }

    private suspend fun replay(existing: InstitutionTransfer, release: ValidatedRelease): InstitutionTransfer {
        if (existing.paymentId != release.paymentId || existing.idempotencyKey != release.idempotencyKey) {
            // Same key, different payment (or the reverse): never a second message, never a silent merge.
            log.errorf(
                "Release for payment %s conflicts with stored transfer %s; dead-lettering",
                release.paymentId,
                existing.id,
            )
            throw InstitutionTransferRefusedException("idempotencyKey and paymentId do not match the stored transfer")
        }
        return if (existing.status == InstitutionTransferStatus.ACCEPTED) submit(existing) else existing
    }

    private suspend fun submit(transfer: InstitutionTransfer): InstitutionTransfer {
        val submission = try {
            gateway.submitInstitutionTransfer(transfer)
        } catch (e: NonConformingMessageException) {
            log.errorf(e, "Rail built a non-conforming pacs.009 for payment %s; rejecting", transfer.paymentId)
            return transition(
                transfer,
                InstitutionTransferStatus.REJECTED,
                "FF01",
                "rail built a non-conforming pacs.009",
                null,
            )
        }
        return when (submission.verdict) {
            InstitutionTransferVerdict.IN_PROCESS ->
                transition(transfer, InstitutionTransferStatus.SENT, null, null, submission.rawPacs009)
            InstitutionTransferVerdict.SETTLED ->
                transition(transfer, InstitutionTransferStatus.SETTLED, null, null, submission.rawPacs009)
            InstitutionTransferVerdict.REJECTED -> transition(
                transfer,
                InstitutionTransferStatus.REJECTED,
                submission.reasonCode ?: "NARR",
                "scheme reject (pacs.002)",
                submission.rawPacs009,
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
            if (status ==
                InstitutionTransferStatus.SETTLED
            ) {
                add(event(updated.copy(status = InstitutionTransferStatus.SENT), now))
            }
            // Strictly later than the SENT row, so the outbox's oldest-first claim keeps the order.
            add(event(updated, if (isEmpty()) now else now.plusMillis(1)))
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
                outbox(paymentId, payload, now, synthetic),
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

    private fun newTransfer(r: ValidatedRelease): InstitutionTransfer {
        val now = Instant.now(clock)
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

    private fun event(t: InstitutionTransfer, at: Instant): OutboxMessage {
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
        return outbox(t.paymentId, payload, at, t.synthetic)
    }

    private fun outbox(paymentId: UUID, payload: InstitutionTransferStatusChanged, at: Instant, synthetic: Boolean) =
        OutboxMessage(
            aggregateId = paymentId,
            eventType = InstitutionTransferStatusChanged.EVENT_TYPE,
            payload = objectMapper.writeValueAsString(payload),
            createdAt = at,
            synthetic = synthetic,
        )
}
