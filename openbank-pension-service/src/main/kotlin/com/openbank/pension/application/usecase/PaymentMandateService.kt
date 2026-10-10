// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.PaymentMandatePort
import com.openbank.pension.domain.contribution.MandateKind
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A regular payment pension-service set up downstream for one contract (V7, #12378). */
data class PaymentMandate(
    val id: UUID,
    val contractId: UUID,
    val kind: MandateKind,
    val externalId: String,
    val status: PaymentMandateStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class PaymentMandateStatus { ACTIVE, CANCELLED }

interface PaymentMandateRepository {
    /** Inserts unless (kind, externalId) is already recorded; returns the stored row either way. */
    suspend fun recordIfAbsent(mandate: PaymentMandate): PaymentMandate

    suspend fun findById(id: UUID): PaymentMandate?

    suspend fun markCancelled(id: UUID, at: Instant)
}

/**
 * account-service: the account id of [iban] when, and only when, it is a verified account held by
 * [partyId]; null otherwise. The mandate's debit account is ALWAYS resolved through this — never
 * taken from the caller (#12378 security review).
 */
interface ParticipantAccountPort {
    suspend fun ownAccountId(partyId: UUID, iban: String): UUID?
}

class ForeignDebtorAccountException :
    RuntimeException("the debtor account is not a verified account of the participant")

class PaymentMandateNotFoundException(id: UUID) : NoSuchElementException("payment mandate $id not found")

/**
 * Sets up and cancels the participant's regular payment (ADR-0334 §4 Contribute, #12378).
 *
 * Set-up is validated by [ContributionService.setUpMandate] (contract state, currency, amount,
 * IBAN, and the contract's own payment reference substituted for anything the caller sent) and
 * then recorded against the contract. Cancellation accepts only a mandate recorded for THAT
 * contract: the downstream id is never taken from the caller, so a participant can cancel their
 * own standing order and nobody else's — a foreign or unknown mandate is a 404, indistinguishable
 * from a missing one.
 */
class PaymentMandateService(
    private val contributions: ContributionService,
    private val port: PaymentMandatePort,
    private val mandates: PaymentMandateRepository,
    private val accounts: ParticipantAccountPort,
    private val clock: Clock,
) {
    suspend fun setUp(request: MandateRequest): PaymentMandate {
        val iban = request.debtorIban.replace(" ", "").uppercase()
        val accountId = accounts.ownAccountId(request.participantPartyId, iban) ?: throw ForeignDebtorAccountException()
        val externalId = contributions.setUpMandate(request.copy(debtorIban = iban, debtorAccountId = accountId))
        val now = clock.instant()
        return mandates.recordIfAbsent(
            PaymentMandate(
                Ids.newId(),
                request.contractId,
                request.kind,
                externalId,
                PaymentMandateStatus.ACTIVE,
                now,
                now,
            ),
        )
    }

    suspend fun cancel(contractId: UUID, mandateId: UUID): PaymentMandate {
        val mandate = mandates.findById(mandateId)?.takeIf { it.contractId == contractId }
            ?: throw PaymentMandateNotFoundException(mandateId)
        if (mandate.status == PaymentMandateStatus.CANCELLED) return mandate
        // Downstream first: if the rail refuses, the row stays ACTIVE and says the truth.
        port.cancel(mandate.kind, mandate.externalId)
        val now = clock.instant()
        mandates.markCancelled(mandate.id, now)
        return mandate.copy(status = PaymentMandateStatus.CANCELLED, updatedAt = now)
    }
}
