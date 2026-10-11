// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit

import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import java.time.Clock

/** An exit aggregate that does not exist, or does not belong to the caller's contract — both a 404. */
class ExitNotFoundException(message: String) : RuntimeException(message)

/** A signature or account check that failed — 403, never a hint about which part failed. */
class ExitForbiddenException(message: String) : RuntimeException(message)

/** Persistence the exit services share; grouped so no service constructor grows past the fleet bound. */
data class ExitStores(
    val contracts: PensionContractRepository,
    val notices: TerminationNoticeRepository,
    val payouts: PayoutRequestRepository,
    val claims: DeathClaimRepository,
    val instructions: PaymentInstructionRepository,
)

/** The outbound systems an exit touches. */
data class ExitGateways(
    val fund: FundAdministrationPort,
    val incentives: IncentiveClawbackPort,
    val tax: TaxWithholdingPort,
    val payments: PayoutPaymentPort,
    val annuities: AnnuityPlacementPort,
    val accounts: OwnAccountVerificationPort,
    val sca: ScaVerificationPort,
    val beneficiaryKyc: BeneficiaryVerificationPort,
    val notifications: ParticipantNotificationPort,
    /** Informational participant notices (#12379), e.g. a payout handed to the rail. */
    val notifier: ParticipantNotifier,
)

data class ExitContext(
    val stores: ExitStores,
    val gateways: ExitGateways,
    val packs: JurisdictionPackRegistry,
    val clock: Clock,
)

internal object IbanRule {
    private val SHAPE = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")

    fun normalise(raw: String): String {
        val iban = raw.replace(" ", "").uppercase()
        require(SHAPE.matches(iban)) { "iban is not a valid IBAN" }
        return iban
    }
}

/** SCA over the quote hash, then the payout account: both a 403, neither says which part failed. */
internal suspend fun ExitGateways.verifySignatureAndAccount(
    partyId: java.util.UUID,
    challenge: String,
    hash: String,
    iban: String,
) {
    if (!sca.verify(partyId, challenge, hash, ScaOperation.EXIT)) {
        throw ExitForbiddenException("strong customer authentication failed for this quote")
    }
    if (!accounts.isOwnVerifiedAccount(partyId, iban)) {
        throw ExitForbiddenException("the payout account is not a verified account of the participant")
    }
}

internal fun requireKey(key: String?): String {
    val k = requireNotNull(key) { "header 'Idempotency-Key' is required" }
    require(k.isNotBlank() && k.length <= MAX_KEY_LENGTH) { "Idempotency-Key must be 1..$MAX_KEY_LENGTH characters" }
    return k
}

private const val MAX_KEY_LENGTH = 128
