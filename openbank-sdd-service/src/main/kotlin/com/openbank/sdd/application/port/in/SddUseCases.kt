// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.port.`in`

import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.authorise.DebtorControls
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sdd.domain.refund.RefundDecision
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/** Command to register a new debtor mandate. */
data class RegisterMandateCommand(
    val accountId: UUID,
    val debtorIban: String,
    val creditorIdentifier: String,
    val umr: String,
    val scheme: SddScheme,
    val sequenceType: SequenceType,
    val creditorName: String,
    val debtorName: String,
    val signatureDate: LocalDate,
)

/** The fields a mandate amendment may change (drives the `AMDT` markers). */
enum class AmendableField { CREDITOR_NAME, CREDITOR_IDENTIFIER, UMR, DEBTOR_IBAN, SEQUENCE_TYPE }

data class AmendMandateCommand(val field: AmendableField, val newValue: String)

interface RegisterMandateUseCase {
    /** Idempotent on `(creditorIdentifier, umr)`. Core ⇒ ACTIVE, B2B ⇒ PENDING_CONFIRMATION. */
    fun register(command: RegisterMandateCommand): Uni<SddMandate>
}

interface ConfirmMandateUseCase {
    fun confirm(mandateId: UUID): Uni<SddMandate>
}

interface ManageMandateUseCase {
    fun suspend(mandateId: UUID): Uni<SddMandate>
    fun resume(mandateId: UUID): Uni<SddMandate>
    fun cancel(mandateId: UUID): Uni<SddMandate>
}

interface AmendMandateUseCase {
    fun amend(mandateId: UUID, command: AmendMandateCommand): Uni<SddMandate>
}

interface AuthoriseCollectionUseCase {
    /** Fail-closed decision; on Accept it stamps the collection and emits `sdd.collection.authorised`. */
    fun authorise(instruction: CollectionInstruction, controls: DebtorControls): Uni<AuthorisationResult>
}

interface AssessRefundUseCase {
    fun assessRefund(mandateId: UUID, debitDate: LocalDate, asOf: LocalDate): Uni<RefundDecision>
}

interface ListMandatesUseCase {
    fun list(accountId: UUID): Uni<List<SddMandate>>
    fun get(id: UUID): Uni<SddMandate>

    /** Backoffice queue (ADR-0230 D1): newest mandates fleet-wide, optionally one status. */
    fun listRecent(status: String?, limit: Int): Uni<List<SddMandate>>
}
