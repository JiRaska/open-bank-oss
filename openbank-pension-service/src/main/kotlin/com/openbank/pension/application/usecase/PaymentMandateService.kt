// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.exit.ScaVerificationPort
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

    /** Staff view (operator mandates list): newest first, optionally for one contract and/or status. */
    suspend fun list(contractId: UUID?, status: PaymentMandateStatus?, limit: Int): List<PaymentMandate>
}

/**
 * account-service: the account id of [iban] when, and only when, it is a verified account held by
 * [partyId]; null otherwise. The mandate's debit account is ALWAYS resolved through this — never
 * taken from the caller (#12378 security review).
 */
interface ParticipantAccountPort {
    suspend fun ownAccountId(partyId: UUID, iban: String): UUID?
}

/**
 * The exact document a mandate set-up challenge signs: contract, kind, debtor IBAN (normalised),
 * amount, currency and first collection. A challenge raised for another IBAN, another contract or
 * another amount has a different hash and is refused, and sca-service burns it only once.
 */
object PaymentMandateSetup {
    fun documentHash(request: MandateRequest): String {
        val iban = request.debtorIban.replace(" ", "").uppercase()
        val doc = listOf(
            "pension-mandate-setup",
            request.contractId,
            request.kind,
            iban,
            request.amount.stripTrailingZeros().toPlainString(),
            request.currency.uppercase(),
            request.firstCollection,
        ).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(doc.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Documents the cancellation and employer-enrolment challenges sign. */
object PaymentMandateLifecycle {
    fun cancellationHash(contractId: UUID, mandateId: UUID): String =
        sha("pension-mandate-cancel|$contractId|$mandateId")

    fun employerEnrolmentHash(contractId: UUID, employerPartyId: UUID): String =
        sha("pension-employer-enrolment|$contractId|$employerPartyId")

    private fun sha(doc: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(doc.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class MandateCancellationScaFailedException :
    RuntimeException("strong customer authentication failed for this cancellation")

class EmployerEnrolmentScaFailedException :
    RuntimeException("strong customer authentication failed for this employer authorisation")

/** The participant's SCA over the mandate document was not verified (403); nothing was looked up or sent. */
class MandateSetupScaFailedException : RuntimeException("strong customer authentication failed for this mandate")

class ForeignDebtorAccountException :
    RuntimeException("the debtor account is not a verified account of the participant")

class PaymentMandateNotFoundException(id: UUID) : NoSuchElementException("payment mandate $id not found")

/** Upper bound of one operator mandates-list page. */
const val MAX_MANDATE_LIST = 200

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
    private val sca: ScaVerificationPort,
    private val clock: Clock,
) {
    /** The operator mandates list; staff-only at the resource and in OPA. [limit] is 1..[MAX_MANDATE_LIST]. */
    suspend fun list(contractId: UUID?, status: PaymentMandateStatus?, limit: Int): List<PaymentMandate> {
        require(limit in 1..MAX_MANDATE_LIST) { "query parameter 'limit' must be between 1 and $MAX_MANDATE_LIST" }
        return mandates.list(contractId, status, limit)
    }

    /**
     * Binds the participant's account to a recurring debit, so it is SCA-gated like every sibling
     * (mandate cancel, payout confirm, payout-account change): the single-use challenge must sign
     * `pension-mandate-setup:<documentHash>` (ADR-0335 namespace) BEFORE the account is looked up
     * or any rail is called. There is no operator or system path to this method.
     */
    suspend fun setUp(request: MandateRequest, scaChallengeId: String?): PaymentMandate {
        requireMandateSca(request, scaChallengeId)
        val iban = request.debtorIban.replace(" ", "").uppercase()
        val accountId = accounts.ownAccountId(request.participantPartyId, iban) ?: throw ForeignDebtorAccountException()
        val externalId = contributions.setUpMandate(request.copy(debtorIban = iban, debtorAccountId = accountId))
        val now = clock.instant()
        return mandates.recordIfAbsent(
            PaymentMandate(
                UUID.randomUUID(),
                request.contractId,
                request.kind,
                externalId,
                PaymentMandateStatus.ACTIVE,
                now,
                now,
            ),
        )
    }

    /**
     * Authorises an employer to contribute through its bulk files (#12378): a binding of a third
     * party to the contract, so SCA-bound like every sibling. Already enrolled = idempotent no-op.
     */
    suspend fun enrolEmployer(
        contractId: UUID,
        participantPartyId: UUID,
        employerPartyId: UUID,
        scaChallengeId: String?,
    ) {
        if (contributions.isEmployerEnrolled(contractId, employerPartyId)) return
        requireSca(
            participantPartyId,
            scaChallengeId,
            PaymentMandateLifecycle.employerEnrolmentHash(contractId, employerPartyId),
            ScaOperation.EMPLOYER_ENROLMENT,
        ) { EmployerEnrolmentScaFailedException() }
        contributions.enrolEmployer(contractId, employerPartyId)
    }

    private suspend fun requireSca(
        partyId: UUID,
        scaChallengeId: String?,
        hash: String,
        operation: ScaOperation,
        failure: () -> RuntimeException,
    ) {
        val challenge = scaChallengeId?.takeIf { it.isNotBlank() }
        if (challenge == null || !sca.verify(partyId, challenge, hash, operation)) throw failure()
    }

    private suspend fun requireMandateSca(request: MandateRequest, scaChallengeId: String?) {
        val challenge = scaChallengeId?.takeIf { it.isNotBlank() }
        val verified = challenge != null &&
            sca.verify(
                request.participantPartyId,
                challenge,
                PaymentMandateSetup.documentHash(request),
                ScaOperation.MANDATE_SETUP,
            )
        if (!verified) throw MandateSetupScaFailedException()
    }

    /**
     * Cancels a regular payment of THIS contract, SCA-bound in the use case (not only in REST): the
     * challenge must sign `pension-mandate-cancellation:<hash(contract, mandate)>`. An already
     * cancelled mandate is an idempotent no-op that spends nothing.
     */
    suspend fun cancel(
        contractId: UUID,
        participantPartyId: UUID,
        mandateId: UUID,
        scaChallengeId: String?,
    ): PaymentMandate {
        val mandate = mandates.findById(mandateId)?.takeIf { it.contractId == contractId }
            ?: throw PaymentMandateNotFoundException(mandateId)
        if (mandate.status == PaymentMandateStatus.CANCELLED) return mandate
        requireSca(
            participantPartyId,
            scaChallengeId,
            PaymentMandateLifecycle.cancellationHash(contractId, mandateId),
            ScaOperation.MANDATE_CANCELLATION,
        ) { MandateCancellationScaFailedException() }
        // Downstream first: if the rail refuses, the row stays ACTIVE and says the truth.
        port.cancel(mandate.kind, mandate.externalId)
        val now = clock.instant()
        mandates.markCancelled(mandate.id, now)
        return mandate.copy(status = PaymentMandateStatus.CANCELLED, updatedAt = now)
    }
}
