// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sdd.application.port.`in`.AmendMandateCommand
import com.openbank.sdd.application.port.`in`.AmendMandateUseCase
import com.openbank.sdd.application.port.`in`.AmendableField
import com.openbank.sdd.application.port.`in`.AssessRefundUseCase
import com.openbank.sdd.application.port.`in`.AuthoriseCollectionUseCase
import com.openbank.sdd.application.port.`in`.ConfirmMandateUseCase
import com.openbank.sdd.application.port.`in`.ListMandatesUseCase
import com.openbank.sdd.application.port.`in`.ManageMandateUseCase
import com.openbank.sdd.application.port.`in`.RegisterMandateCommand
import com.openbank.sdd.application.port.`in`.RegisterMandateUseCase
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.application.port.out.SddOutbox
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionAuthorisationPolicy
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.authorise.DebtorControls
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sdd.domain.refund.RefundDecision
import com.openbank.sdd.domain.refund.RefundPolicy
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Orchestrates the debtor-side SDD lifecycle (ADR-0036). Every decision (lifecycle transition,
 * collection authorisation, refund eligibility) lives in the framework-free domain; this use case
 * only wires the ports and persists/emits. v1 never moves money — an authorised collection emits
 * `sdd.collection.authorised` for the ledger/payment path to execute (§C).
 */
@ApplicationScoped
class SddMandateService(private val mandates: SddMandateRepository, private val outbox: SddOutbox) :
    RegisterMandateUseCase,
    ConfirmMandateUseCase,
    ManageMandateUseCase,
    AmendMandateUseCase,
    AuthoriseCollectionUseCase,
    AssessRefundUseCase,
    ListMandatesUseCase {

    /** Clock seam (overridable in tests; CDI uses the default). */
    internal var clock: () -> Instant = Instant::now

    override fun register(command: RegisterMandateCommand): Uni<SddMandate> =
        mandates.findByReference(command.creditorIdentifier, command.umr).flatMap { existing ->
            if (existing != null) {
                // Idempotent: re-registering the same (CID, UMR) returns the stored mandate.
                Uni.createFrom().item(existing)
            } else {
                val mandate = SddMandate(
                    id = UUID.randomUUID(),
                    accountId = command.accountId,
                    debtorIban = command.debtorIban,
                    creditorIdentifier = command.creditorIdentifier,
                    umr = command.umr,
                    scheme = command.scheme,
                    sequenceType = command.sequenceType,
                    creditorName = command.creditorName,
                    debtorName = command.debtorName,
                    signatureDate = command.signatureDate,
                    // B2B must be confirmed by the debtor bank before it can authorise; Core is born live.
                    status = if (command.scheme == SddScheme.B2B) {
                        MandateStatus.PENDING_CONFIRMATION
                    } else {
                        MandateStatus.ACTIVE
                    },
                    b2bConfirmed = false,
                    lastCollectionDate = null,
                    lastPreNotificationDate = null,
                    createdAt = clock(),
                )
                persist(mandate, "sdd.mandate.registered.v1")
            }
        }

    override fun confirm(mandateId: UUID): Uni<SddMandate> =
        transition(mandateId, "sdd.mandate.confirmed.v1") { MandateLifecycle.confirm(it) }

    override fun suspend(mandateId: UUID): Uni<SddMandate> =
        transition(mandateId, "sdd.mandate.suspended.v1") { MandateLifecycle.suspend(it) }

    override fun resume(mandateId: UUID): Uni<SddMandate> =
        transition(mandateId, "sdd.mandate.resumed.v1") { MandateLifecycle.resume(it) }

    override fun cancel(mandateId: UUID): Uni<SddMandate> =
        transition(mandateId, "sdd.mandate.cancelled.v1") { MandateLifecycle.cancel(it) }

    override fun amend(mandateId: UUID, command: AmendMandateCommand): Uni<SddMandate> =
        load(mandateId).flatMap { mandate ->
            val (field, oldValue) = currentValue(mandate, command.field)
            val amended = MandateLifecycle.amend(mandate, field, oldValue, command.newValue, clock())
            persist(applyField(amended, command), "sdd.mandate.amended.v1")
        }

    override fun authorise(instruction: CollectionInstruction, controls: DebtorControls): Uni<AuthorisationResult> =
        mandates.findByReference(instruction.creditorIdentifier, instruction.umr).flatMap { mandate ->
            when (val result = CollectionAuthorisationPolicy.authorise(mandate, instruction, controls)) {
                is AuthorisationResult.Accept -> {
                    // Replay guard (#8351): an authorised collection is uniquely identified by
                    // (mandateId, umr, dueDate) — that triple is the deterministic dedup key the
                    // debit consumer books under (`so-sdd-{mandateId}-{umr}-{dueDate}`). A retried
                    // authorise for the SAME dueDate must not re-stamp and re-emit: the duplicate
                    // event would be deduped downstream, but emitting it at all makes the retry
                    // observable only as noise in the outbox. lastCollectionDate == dueDate means
                    // this exact collection was already authorised — return the same decision
                    // without side effects. A different dueDate is a NEW collection, not a retry.
                    if (mandate!!.lastCollectionDate == instruction.dueDate) {
                        return@flatMap Uni.createFrom().item(result)
                    }
                    // Stamp the settled collection and emit for the downstream posting path.
                    val stamped = MandateLifecycle.recordCollection(mandate, instruction.dueDate)
                    mandates.save(stamped)
                        .flatMap { saved -> outbox.append(collectionAuthorisedEvent(saved, instruction)).map { saved } }
                        .map { result }
                }
                else -> Uni.createFrom().item(result)
            }
        }

    override fun assessRefund(mandateId: UUID, debitDate: LocalDate, asOf: LocalDate): Uni<RefundDecision> =
        load(mandateId).map { mandate ->
            // A stored mandate means the collection was authorised; unauthorised refunds are handled
            // where no mandate exists (not modelled as a use case in v1).
            RefundPolicy.assess(mandate.scheme, debitDate, asOf, authorised = true)
        }

    override fun list(accountId: UUID): Uni<List<SddMandate>> = mandates.listForAccount(accountId)

    override fun listRecent(status: String?, limit: Int): Uni<List<SddMandate>> =
        mandates.findRecent(status, limit.coerceIn(1, MAX_LIST_LIMIT))

    override fun get(mandateId: UUID): Uni<SddMandate> = load(mandateId)

    // --- helpers -------------------------------------------------------------------------------

    private fun transition(mandateId: UUID, eventType: String, f: (SddMandate) -> SddMandate): Uni<SddMandate> =
        load(mandateId).flatMap { persist(f(it), eventType) }

    private fun load(mandateId: UUID): Uni<SddMandate> = mandates.findById(mandateId).flatMap { m ->
        if (m == null) Uni.createFrom().failure(MandateNotFoundException(mandateId)) else Uni.createFrom().item(m)
    }

    private fun persist(mandate: SddMandate, eventType: String): Uni<SddMandate> =
        mandates.save(mandate).flatMap { saved ->
            outbox.append(mandateEvent(saved, eventType)).map { saved }
        }

    private fun currentValue(m: SddMandate, field: AmendableField): Pair<String, String> = when (field) {
        AmendableField.CREDITOR_NAME -> "creditorName" to m.creditorName
        AmendableField.CREDITOR_IDENTIFIER -> "creditorIdentifier" to m.creditorIdentifier
        AmendableField.UMR -> "umr" to m.umr
        AmendableField.DEBTOR_IBAN -> "debtorIban" to m.debtorIban
        AmendableField.SEQUENCE_TYPE -> "sequenceType" to m.sequenceType.name
    }

    private fun applyField(m: SddMandate, cmd: AmendMandateCommand): SddMandate = when (cmd.field) {
        AmendableField.CREDITOR_NAME -> m.copy(creditorName = cmd.newValue)
        AmendableField.CREDITOR_IDENTIFIER -> m.copy(creditorIdentifier = cmd.newValue)
        AmendableField.UMR -> m.copy(umr = cmd.newValue)
        AmendableField.DEBTOR_IBAN -> m.copy(debtorIban = cmd.newValue)
        AmendableField.SEQUENCE_TYPE -> m.copy(sequenceType = SequenceType.valueOf(cmd.newValue))
    }

    private fun mandateEvent(m: SddMandate, eventType: String): OutboxMessage {
        val payload = """
            {"eventType":"$eventType",
            "mandateId":"${m.id}",
            "accountId":"${m.accountId}",
            "creditorIdentifier":"${m.creditorIdentifier}",
            "umr":"${m.umr}",
            "scheme":"${m.scheme}",
            "status":"${m.status}",
            "sequenceType":"${m.sequenceType}",
            "occurredAt":"${clock()}"}
        """.trimIndent().replace("\n", "")
        return OutboxMessage(UUID.randomUUID(), m.id, eventType, payload)
    }

    private fun collectionAuthorisedEvent(m: SddMandate, i: CollectionInstruction): OutboxMessage {
        val eventType = "sdd.collection.authorised.v1"
        val payload = """
            {"eventType":"$eventType",
            "mandateId":"${m.id}",
            "accountId":"${m.accountId}",
            "debtorIban":"${m.debtorIban}",
            "creditorIdentifier":"${i.creditorIdentifier}",
            "umr":"${i.umr}",
            "scheme":"${i.scheme}",
            "sequenceType":"${i.sequenceType}",
            "amount":${i.amount.toPlainString()},
            "currency":"${i.currency}",
            "dueDate":"${i.dueDate}",
            "occurredAt":"${clock()}"}
        """.trimIndent().replace("\n", "")
        return OutboxMessage(UUID.randomUUID(), m.id, eventType, payload)
    }

    private companion object {
        const val MAX_LIST_LIMIT = 100
    }
}

/** Raised when a mandate id does not resolve. */
class MandateNotFoundException(val mandateId: UUID) : RuntimeException("No SDD mandate $mandateId")
