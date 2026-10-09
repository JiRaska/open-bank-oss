// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.identity

import com.openbank.pension.application.exit.ClaimantKyc
import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import com.openbank.pension.application.onboarding.KycProfile
import com.openbank.pension.application.onboarding.KycStatus
import jakarta.ws.rs.WebApplicationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/*
 * The DECISIONS of the identity/SCA adapters, free of CDI so every refusal path is unit-tested
 * against a fake provider (ADR-0334, #12377). The CDI beans in IdentityAdapters.kt only wire these
 * to the REST clients.
 *
 * Error policy, the same everywhere: a provider answer that SAYS NO (404 unknown, 409 mismatch, …)
 * is a refusal; a provider that could not be asked (401, 5xx, timeout, connection refused, an
 * authorization deny on our own identity) is IntegrationUnavailableException (503). Neither is
 * ever a pass.
 */

/** What an SCA challenge must have been raised over, as sca-service's APPROVAL dynamic linking. */
data class ScaBinding(val approvalRequestId: String, val payloadSha256: String) {
    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Onboarding / transfer (SignatureVerificationPort): the operation reference names the
         * application or transfer and its counterparty; the payload is the signed document's
         * SHA-256 or, when no document is signed, the SHA-256 of the operation reference itself.
         */
        fun forOperation(operationRef: String, documentSha256: String?): ScaBinding? {
            if (operationRef.isBlank()) return null
            val payload = documentSha256 ?: sha256Hex(operationRef)
            return if (HEX64.matches(payload)) ScaBinding(operationRef, payload.lowercase()) else null
        }

        /**
         * Exit (ScaVerificationPort): the hash already covers the quote AND the payout IBAN
         * (`TerminationNotice/PayoutRequest.signingHash`), so a signature over another account or
         * another quote has a different payload and a different approval request id.
         */
        fun forExit(signingHash: String): ScaBinding? = if (HEX64.matches(
                signingHash,
            )
        ) {
            ScaBinding("$EXIT_PREFIX${signingHash.lowercase()}", signingHash.lowercase())
        } else {
            null
        }

        const val EXIT_PREFIX = "pension-exit:"
    }
}

fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/**
 * Spends an SCA challenge at sca-service: server-side, SINGLE-USE (sca-service refuses a second
 * consume with 409) and bound to [ScaBinding] — sca-service compares approvalRequestId exactly and
 * payloadSha256 case-insensitively, so a challenge signed over any other payload (another payout
 * account, another quote, another document) answers 409 and is NOT burnt.
 *
 * Purpose: sca-service has no pension purpose (its ScaPurpose enum, openapi 1.16.0), so the
 * challenge must be raised as `APPROVAL`, the generic operation-bound purpose. The answer is
 * checked to BE an APPROVAL challenge of this party, COMPLETED and now consumed — a 2xx that is
 * anything else (e.g. a 202 parked for four-eyes approval) is a refusal.
 */
class ScaConsumeGate(private val consume: suspend (UUID, ScaConsumeRequestDto) -> ScaChallengeDto) {

    suspend fun spend(partyId: UUID, challengeId: String, binding: ScaBinding?): Boolean {
        if (binding == null) return false
        val id = runCatching { UUID.fromString(challengeId.trim()) }.getOrNull() ?: return false
        val answer = call(id, ScaConsumeRequestDto(partyId, binding.approvalRequestId, binding.payloadSha256))
            ?: return false
        return answer.id == id &&
            answer.partyId == partyId &&
            answer.purpose == REQUIRED_PURPOSE &&
            answer.status == "COMPLETED" &&
            !answer.consumedAt.isNullOrBlank()
    }

    /** The provider's answer, or null when sca-service SAYS NO; unavailable when it cannot answer. */
    private suspend fun call(id: UUID, request: ScaConsumeRequestDto): ScaChallengeDto? = try {
        consume(id, request)
    } catch (expected: WebApplicationException) {
        when (expected.response?.status) {
            // 400 not approved / invalid, 403 another party's challenge, 404 unknown,
            // 409 already consumed or a dynamic-linking mismatch, 422 expired.
            BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT, UNPROCESSABLE -> null
            else -> unavailable("sca-service could not verify the challenge (${expected.response?.status})", expected)
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        unavailable("sca-service unreachable: ${e.javaClass.simpleName}", e)
    }

    companion object {
        const val REQUIRED_PURPOSE = "APPROVAL"
        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val UNPROCESSABLE = 422
    }
}

/** Runs a read; 404 becomes null, anything but a 2xx becomes IntegrationUnavailableException. */
internal suspend fun <T> readOrNull(provider: String, block: suspend () -> T): T? = try {
    block()
} catch (expected: WebApplicationException) {
    if (expected.response?.status == HTTP_NOT_FOUND) {
        null
    } else {
        unavailable("$provider refused the lookup (${expected.response?.status})", expected)
    }
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    unavailable("$provider unreachable: ${e.javaClass.simpleName}", e)
}

/** 503, keeping the provider failure as the cause for the log. */
internal fun unavailable(message: String, cause: Throwable): Nothing =
    throw IntegrationUnavailableException(message).apply { initCause(cause) }

private const val HTTP_NOT_FOUND = 404

/**
 * party-service's party record -> what KYC established (ADR-0334 §4 "KYC reuse"). party-service
 * mirrors kyc-service's verdict into `kycStatus` (the KycStatusChanged event), so one read serves.
 *
 * - KYC: APPROVED -> VERIFIED, IN_PROGRESS -> PENDING, NOT_STARTED -> NOT_STARTED, REJECTED /
 *   EXPIRED / anything unknown -> REJECTED. A SUSPENDED, CLOSED or MERGED party is REJECTED
 *   whatever its KYC says.
 * - Residency: the address country, only once KYC is APPROVED (otherwise the declaration stands).
 * - Birth date: null — `GET /parties/{id}` does not serve it, so the declaration stands, exactly
 *   as before.
 * - Legal capacity: party-service records no capacity or guardianship register. It is answered
 *   `true` only for an ACTIVE, KYC-approved natural person and `false` otherwise; a court-ordered
 *   restriction is not visible here (follow-up, see the PR).
 */
object PartyKycMapping {
    fun profile(party: PartyDto): KycProfile {
        val closed = party.status in setOf("SUSPENDED", "CLOSED", "MERGED")
        val status = when {
            closed -> KycStatus.REJECTED
            party.kycStatus == "APPROVED" -> KycStatus.VERIFIED
            party.kycStatus == "IN_PROGRESS" -> KycStatus.PENDING
            party.kycStatus == "NOT_STARTED" -> KycStatus.NOT_STARTED
            else -> KycStatus.REJECTED
        }
        val verified = status == KycStatus.VERIFIED
        val country = party.address?.countryCode?.trim()?.uppercase()?.takeIf { verified && it.length == 2 }
        return KycProfile(
            status = status,
            fullLegalCapacity = verified && party.partyType == "INDIVIDUAL" && party.status == "ACTIVE",
            verifiedBirthDate = null,
            verifiedResidencyCountry = country,
        )
    }
}

/** account-service: the IBAN is an ACTIVE account held by exactly this party. */
object AccountOwnership {
    fun owns(partyId: UUID, account: AccountDto?): Boolean =
        account != null && account.partyId == partyId && account.status == "ACTIVE"
}

/**
 * The account holder must match a known claimant party ID. Account and party lookups cannot
 * attest the claimant's document reference or birth date, so even a matching KYC-approved holder
 * cannot be marked VERIFIED until a trusted provider can establish those facts.
 */
class BeneficiaryLightKyc(
    private val accountByIban: suspend (String) -> AccountDto?,
    private val partyById: suspend (UUID) -> PartyDto?,
    private val verifiedIdentityEvidence: suspend (ClaimantKyc, UUID) -> Boolean? = { _, _ -> null },
) {
    suspend fun verify(kyc: ClaimantKyc, claimantPartyId: UUID?): Boolean? {
        if (kyc.identityDocumentRef.isBlank() || kyc.name.isBlank()) return false
        val account = accountByIban(kyc.iban) ?: return false
        val holder = account.partyId?.takeIf { account.status == "ACTIVE" } ?: return false
        if (claimantPartyId != null && holder != claimantPartyId) return false
        val party = partyById(holder) ?: return false
        val holderMatches = party.id == holder &&
            party.partyType == "INDIVIDUAL" &&
            party.status == "ACTIVE" &&
            party.kycStatus == "APPROVED" &&
            normaliseName(party.legalName) == normaliseName(kyc.name)
        if (!holderMatches) return false
        // Neither production lookup verifies identityDocumentRef or birthDate against the claimant.
        return verifiedIdentityEvidence(kyc, holder)
    }

    private fun normaliseName(name: String?): String = name.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase()
}
