// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.identity

import com.openbank.pension.application.exit.ClaimantKyc
import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import com.openbank.pension.application.onboarding.KycStatus
import com.openbank.pension.domain.exit.sha256
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The identity/SCA adapters' decisions (#12377). [FakeSca] reproduces sca-service's consume
 * semantics (ScaService.consume + DynamicLinkingData.authorises): party check (403), single use
 * (409), exact approvalRequestId + case-insensitive payloadSha256 (409, NOT burnt).
 */
class IdentityChecksTest {

    private val party = UUID.randomUUID()
    private val quoteHash = sha256("quote-1")

    /** The exact hash the exit domain signs: quote AND payout account (PayoutRequest.signingHash). */
    private fun signingHash(iban: String) = sha256("$quoteHash|payout-account|$iban")

    private class Challenge(
        val party: UUID,
        val purpose: String,
        val approvalRequestId: String,
        val payloadSha256: String,
        var consumed: Boolean = false,
    )

    private class FakeSca {
        val challenges = mutableMapOf<UUID, Challenge>()
        var calls = 0

        fun raise(party: UUID, binding: ScaBinding, purpose: String = "APPROVAL"): UUID = UUID.randomUUID().also {
            challenges[it] =
                Challenge(party, purpose, binding.approvalRequestId, binding.payloadSha256)
        }

        private fun reject(status: Int): Nothing = throw WebApplicationException(status)

        fun consume(id: UUID, req: ScaConsumeRequestDto): ScaChallengeDto {
            calls++
            val c = challenges[id] ?: reject(404)
            if (c.party != req.partyId) reject(403)
            if (c.consumed) reject(409)
            if (c.approvalRequestId != req.approvalRequestId ||
                !c.payloadSha256.equals(req.payloadSha256, ignoreCase = true)
            ) {
                reject(409)
            }
            c.consumed = true
            return ScaChallengeDto(id, c.party, c.purpose, "COMPLETED", "2026-10-09T10:00:00Z")
        }
    }

    private val sca = FakeSca()
    private val gate = ScaConsumeGate { id, req -> sca.consume(id, req) }

    @Test
    fun `a signature over one payout account is refused for another account`(): Unit = runBlocking {
        val signedIban = "CZ6508000000192000145399"
        val otherIban = "CZ5508000000001234567899"
        val challenge = sca.raise(party, ScaBinding.forDocument(ScaOperation.EXIT, signingHash(signedIban))!!)

        assertThat(
            gate.spend(party, challenge.toString(), ScaBinding.forDocument(ScaOperation.EXIT, signingHash(otherIban))),
        ).isFalse()
        // A mismatch does not burn the challenge: the operation it WAS signed for still passes, once.
        assertThat(
            gate.spend(party, challenge.toString(), ScaBinding.forDocument(ScaOperation.EXIT, signingHash(signedIban))),
        ).isTrue()
        assertThat(
            gate.spend(party, challenge.toString(), ScaBinding.forDocument(ScaOperation.EXIT, signingHash(signedIban))),
        ).isFalse()
    }

    @Test
    fun `a signature over another document or operation is refused`(): Unit = runBlocking {
        val kid = sha256("kid-v1")
        val challenge = sca.raise(party, ScaBinding.forOperation("pension-onboarding:a:kid-1", kid)!!)

        assertThat(
            gate.spend(party, "$challenge", ScaBinding.forOperation("pension-onboarding:a:kid-1", sha256("kid-v2"))),
        ).isFalse()
        assertThat(
            gate.spend(party, "$challenge", ScaBinding.forOperation("pension-onboarding:b:kid-1", kid)),
        ).isFalse()
        assertThat(gate.spend(party, "$challenge", ScaBinding.forOperation("pension-onboarding:a:kid-1", kid))).isTrue()
    }

    @Test
    fun `a transfer with no document binds the hash of its operation reference`(): Unit = runBlocking {
        val binding = ScaBinding.forOperation("pension-transfer:t1:IBAN-X", null)!!
        assertThat(binding.payloadSha256).isEqualTo(sha256Hex("pension-transfer:t1:IBAN-X"))
        val challenge = sca.raise(party, binding)
        assertThat(
            gate.spend(party, "$challenge", ScaBinding.forOperation("pension-transfer:t1:IBAN-Y", null)),
        ).isFalse()
        assertThat(gate.spend(party, "$challenge", binding)).isTrue()
    }

    @Test
    fun `another party's challenge is refused`(): Unit = runBlocking {
        val binding = ScaBinding.forDocument(ScaOperation.EXIT, signingHash("CZ6508000000192000145399"))!!
        val challenge = sca.raise(UUID.randomUUID(), binding)
        assertThat(gate.spend(party, "$challenge", binding)).isFalse()
    }

    @Test
    fun `a consumed challenge raised for another purpose is still refused`(): Unit = runBlocking {
        val binding = ScaBinding.forDocument(ScaOperation.EXIT, signingHash("CZ6508000000192000145399"))!!
        val challenge = sca.raise(party, binding, purpose = "DELEGATION_GRANT")
        assertThat(gate.spend(party, "$challenge", binding)).isFalse()
    }

    @Test
    fun `a 2xx that is not a consumed challenge (four-eyes parked) is refused`(): Unit = runBlocking {
        val parked = ScaConsumeGate { id, req -> ScaChallengeDto(id, req.partyId, "APPROVAL", "COMPLETED", null) }
        assertThat(
            parked.spend(party, "${UUID.randomUUID()}", ScaBinding.forDocument(ScaOperation.EXIT, quoteHash)),
        ).isFalse()
    }

    @Test
    fun `malformed input never reaches sca-service`(): Unit = runBlocking {
        assertThat(gate.spend(party, "not-a-uuid", ScaBinding.forDocument(ScaOperation.EXIT, quoteHash))).isFalse()
        assertThat(
            gate.spend(party, "${UUID.randomUUID()}", ScaBinding.forDocument(ScaOperation.EXIT, "not-hex")),
        ).isFalse()
        assertThat(gate.spend(party, "${UUID.randomUUID()}", ScaBinding.forOperation("op", "short"))).isFalse()
        assertThat(sca.calls).isZero()
    }

    @Test
    fun `an sca-service that cannot answer is unavailable, never a pass`() {
        for (status in listOf(401, 500, 503)) {
            val down = ScaConsumeGate { _, _ -> throw WebApplicationException(status) }
            assertThatThrownBy {
                runBlocking {
                    down.spend(party, "${UUID.randomUUID()}", ScaBinding.forDocument(ScaOperation.EXIT, quoteHash))
                }
            }
                .isInstanceOf(IntegrationUnavailableException::class.java)
        }
        val refused = ScaConsumeGate { _, _ -> throw java.net.ConnectException("refused") }
        assertThatThrownBy {
            runBlocking {
                refused.spend(party, "${UUID.randomUUID()}", ScaBinding.forDocument(ScaOperation.EXIT, quoteHash))
            }
        }
            .isInstanceOf(IntegrationUnavailableException::class.java)
    }

    @Test
    fun `party kyc verdicts map without ever upgrading`() {
        fun p(kyc: String?, status: String = "ACTIVE", type: String = "INDIVIDUAL", country: String? = "cz") =
            PartyKycMapping.profile(
                PartyDto(UUID.randomUUID(), type, status, "Jan Novák", kyc, PartyAddressDto(country)),
            )

        val ok = p("APPROVED")
        assertThat(ok.status).isEqualTo(KycStatus.VERIFIED)
        assertThat(ok.fullLegalCapacity).isTrue()
        assertThat(ok.verifiedResidencyCountry).isEqualTo("CZ")
        assertThat(ok.verifiedBirthDate).isNull()
        assertThat(p("IN_PROGRESS").status).isEqualTo(KycStatus.PENDING)
        assertThat(p("IN_PROGRESS").verifiedResidencyCountry).isNull()
        assertThat(p("NOT_STARTED").status).isEqualTo(KycStatus.NOT_STARTED)
        assertThat(p("EXPIRED").status).isEqualTo(KycStatus.REJECTED)
        assertThat(p(null).status).isEqualTo(KycStatus.REJECTED)
        assertThat(p("APPROVED", status = "SUSPENDED").status).isEqualTo(KycStatus.REJECTED)
        assertThat(p("APPROVED", type = "COMPANY").fullLegalCapacity).isFalse()
        assertThat(p("APPROVED", country = "CZE").verifiedResidencyCountry).isNull()
    }

    @Test
    fun `an own account is an owned AND active verdict of the ownership projection`() {
        assertThat(
            AccountOwnership.owns(OwnershipVerificationDto(owned = true, active = true, UUID.randomUUID())),
        ).isTrue()
        assertThat(AccountOwnership.owns(OwnershipVerificationDto(owned = false, active = false))).isFalse()
        assertThat(AccountOwnership.owns(OwnershipVerificationDto(owned = true, active = false))).isFalse()
        assertThat(AccountOwnership.owns(null)).isFalse()
    }

    @Test
    fun `a challenge raised for one pension operation cannot be spent on another`(): Unit = runBlocking {
        val hash = sha256("schedule-plan")
        val challenge = sca.raise(party, ScaBinding.forDocument(ScaOperation.SCHEDULE_CHANGE, hash)!!)
        assertThat(
            gate.spend(party, "$challenge", ScaBinding.forDocument(ScaOperation.BENEFICIARY_CHANGE, hash)),
        ).isFalse()
        assertThat(gate.spend(party, "$challenge", ScaBinding.forDocument(ScaOperation.SCHEDULE_CHANGE, hash))).isTrue()
    }

    @Test
    fun `an id outside the reserved pension namespace never reaches sca-service`(): Unit = runBlocking {
        val hash = sha256("x")
        assertThat(gate.spend(party, "${UUID.randomUUID()}", ScaBinding.forOperation("transfer:t1", hash))).isFalse()
        val tooLong = "pension-transfer-out:" + "a".repeat(ScaBinding.MAX_APPROVAL_REQUEST_ID)
        assertThat(gate.spend(party, "${UUID.randomUUID()}", ScaBinding.forOperation(tooLong, hash))).isFalse()
        assertThat(sca.calls).isZero()
        assertThat(ScaOperation.entries.map { ScaBinding.isReserved(it.approvalRequestId(hash)) }).containsOnly(true)
    }

    @Test
    fun `a 404 lookup is an absence and anything else is unavailable`(): Unit = runBlocking {
        assertThat(readOrNull<String>("x") { throw WebApplicationException(404) } == null).isTrue()
        assertThatThrownBy { runBlocking { readOrNull<String>("x") { throw WebApplicationException(403) } } }
            .isInstanceOf(IntegrationUnavailableException::class.java)
        Unit
    }

    @Test
    fun `beneficiary light kyc needs a KYC-approved claimant who owns the IBAN under the claimed name`(): Unit =
        runBlocking {
            val holder = UUID.randomUUID()
            val iban = "CZ6508000000192000145399"
            val asked = mutableListOf<Pair<String, UUID>>()
            val ownership: suspend (String, UUID) -> OwnershipVerificationDto? = { i, p ->
                asked += i to p
                OwnershipVerificationDto(owned = i == iban && p == holder, active = i == iban && p == holder)
            }
            var partyRecord = PartyDto(holder, "INDIVIDUAL", "ACTIVE", "Jana  Nováková", "APPROVED")
            val check = BeneficiaryLightKyc(ownership, { if (it == holder) partyRecord else null })
            val kyc = ClaimantKyc("jana nováková", LocalDate.of(1970, 1, 1), "OP-123", iban, holder)

            assertThat(check.verify(kyc)).isTrue()
            // The question is "is this the CLAIMANT's IBAN", never "who holds this IBAN".
            assertThat(asked).containsExactly(iban to holder)
            assertThat(check.verify(kyc.copy(name = "Petr Novák"))).isFalse()
            assertThat(check.verify(kyc.copy(identityDocumentRef = " "))).isFalse()
            assertThat(check.verify(kyc.copy(iban = "CZ5508000000001234567899"))).isFalse()
            assertThat(check.verify(kyc.copy(partyId = UUID.randomUUID()))).isFalse()
            assertThat(check.verify(kyc.copy(partyId = null))).isFalse()
            partyRecord = partyRecord.copy(kycStatus = "IN_PROGRESS")
            assertThat(check.verify(kyc)).isFalse()
        }
}
