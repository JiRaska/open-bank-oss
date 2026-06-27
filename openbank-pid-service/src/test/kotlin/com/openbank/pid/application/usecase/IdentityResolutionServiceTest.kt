// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.libs.identity.BlindIndex
import com.openbank.pid.application.port.`in`.*
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.domain.model.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for the three-tier identity resolution logic (ADR-0072).
 *
 * No framework boot; all dependencies mocked. The pepper is a fixed 32-byte test value.
 */
class IdentityResolutionServiceTest {

    // ── fixtures ──────────────────────────────────────────────────────────────

    // A known-valid Czech RČ: 760506/0001
    // birthdate = 1976-05-06, gender = MALE
    // checksum: firstNine=760506000, 760506000%11=1, checkDigit=1 → valid
    private val validRc = "7605060001"
    private val validRcBirthdate = LocalDate.of(1976, 5, 6)

    // A fixed pepper for tests (32 bytes, hex-encoded).
    private val testPepperHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val testPepperBytes: ByteArray
        get() = testPepperHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val existingPartyId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    // The caseId the adjudication port returns when a fresh four-eyes case is opened.
    private val testCaseId: UUID = UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000")

    private lateinit var repo: PartyRepository
    private lateinit var adjudication: IdentityAdjudicationUseCase
    private lateinit var svc: IdentityResolutionService

    @BeforeEach
    fun setUp() {
        repo = mockk()
        adjudication = mockk()
        // Default: no prior decision; opening a case yields the fixed test caseId.
        coEvery { adjudication.priorDecision(any()) } returns null
        coEvery { adjudication.openOrReuse(any()) } returns testCaseId
        // Default: no tier-2′ probabilistic candidates (tests that need them override this).
        coEvery { repo.findCandidatesForProbabilistic(any(), any()) } returns emptyList()
        svc = IdentityResolutionService(repo, adjudication, Optional.of(testPepperHex))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun blindIndexOf(rc: String): String {
        val canonical = rc.replace("/", "").trim()
        return BlindIndex.compute(testPepperBytes, canonical)
    }

    private fun makeParty(
        id: UUID = existingPartyId,
        birthdate: LocalDate = validRcBirthdate,
        gender: Gender? = Gender.MALE,
        familyName: String = "Novak",
        givenName: String = "Jan",
        birthplace: String? = null,
    ): Party = Party(
        id = id,
        partyType = PartyType.NATURAL_PERSON,
        status = PartyStatus.ACTIVE,
        externalIds = emptyList(),
        coreAttributes = CoreAttributes(
            givenName = givenName, familyName = familyName,
            birthdate = birthdate, birthNumberEncrypted = null,
            gender = gender, birthplace = birthplace,
            nationalities = listOf("CZ"), idDocuments = emptyList(),
            verificationSource = VerificationSource.BANKID,
            verifiedAt = OffsetDateTime.now(),
        ),
        addressAttributes = null,
        contactAttributes = ContactAttributes(null, null, null, null),
        kycAttributes = KycAttributes(KycLevel.BASIC, null, null, AmlRiskScore.LOW, false, false, null, null),
        relationships = emptyList(),
        caseLifecycle = null,
        createdAt = OffsetDateTime.now(), updatedAt = OffsetDateTime.now(), version = 0,
    )

    private fun cmd(
        givenName: String = "Jan",
        familyName: String = "Novak",
        birthdate: LocalDate = validRcBirthdate,
        birthplace: String? = null,
        birthNumberRaw: String? = null,
        eudiPidSubVerified: String? = null,
    ) = ResolveIdentityCommand(
        givenName,
        familyName,
        birthdate,
        birthplace,
        birthNumberRaw,
        eudiPidSubVerified = eudiPidSubVerified,
    )

    // ── tier-0: verified EUDI PID (ADR-0094) ───────────────────────────────────

    @Test
    fun `tier-0 — verified EUDI PID match returns MATCH_EXISTING above RC`(): Unit = runBlocking {
        coEvery { repo.findByExternalId(ExternalIdType.EUDI_PID_SUB, any()) } returns makeParty()

        // RČ also present, but tier-0 runs first and wins.
        val result = svc.resolve(cmd(birthNumberRaw = validRc, eudiPidSubVerified = "sub:CZ-PID-0001"))

        assertThat(result).isInstanceOf(ResolutionResult.MatchExisting::class.java)
        assertThat((result as ResolutionResult.MatchExisting).partyId).isEqualTo(existingPartyId)
        coVerify(exactly = 0) { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, any()) } // tier-1 skipped
    }

    @Test
    fun `tier-0 — no EUDI PID match falls through to tier-1`(): Unit = runBlocking {
        coEvery { repo.findByExternalId(ExternalIdType.EUDI_PID_SUB, any()) } returns null
        val bi = blindIndexOf(validRc)
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns makeParty()

        val result = svc.resolve(cmd(birthNumberRaw = validRc, eudiPidSubVerified = "sub:UNKNOWN"))

        assertThat(result).isInstanceOf(ResolutionResult.MatchExisting::class.java) // matched via tier-1
        coVerify(exactly = 1) { repo.findByExternalId(ExternalIdType.EUDI_PID_SUB, any()) }
    }

    // ── tier-1: RČ blind index ─────────────────────────────────────────────────

    @Test
    fun `tier-1 — valid RC with matching party returns MATCH_EXISTING`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns makeParty()
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList() // defensive: should NOT be called

        val result = svc.resolve(cmd(birthNumberRaw = validRc))

        assertThat(result).isInstanceOf(ResolutionResult.MatchExisting::class.java)
        assertThat((result as ResolutionResult.MatchExisting).partyId).isEqualTo(existingPartyId)
        coVerify(exactly = 0) { repo.findCandidatesByMatchKey(any()) }
    }

    @Test
    fun `tier-1 — RC with no existing party returns NO_MATCH without falling to tier-2`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns null
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList() // defensive: should NOT be called

        val result = svc.resolve(cmd(birthNumberRaw = validRc))

        assertThat(result).isEqualTo(ResolutionResult.NoMatch)
        // tier-2 must NOT have been consulted
        coVerify(exactly = 0) { repo.findCandidatesByMatchKey(any()) }
    }

    @Test
    fun `tier-1 — RC collision divergent birthdate escalates to NEEDS_MANUAL with RN_COLLISION trigger`(): Unit =
        runBlocking {
            val bi = blindIndexOf(validRc)
            // stored party has a different birthdate → collision; tier-2 must NOT run
            val divergentParty = makeParty(birthdate = LocalDate.of(1980, 1, 1))
            coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns divergentParty

            val result = svc.resolve(cmd(birthNumberRaw = validRc))

            assertThat(result).isInstanceOf(ResolutionResult.NeedsManualVerification::class.java)
            val nmv = result as ResolutionResult.NeedsManualVerification
            assertThat(nmv.trigger).isEqualTo(VerificationTrigger.RN_COLLISION)
            assertThat(nmv.candidates).hasSize(1)
            assertThat(nmv.candidates.first().partyId).isEqualTo(existingPartyId)
            // four-eyes case opened → real caseId returned
            assertThat(nmv.caseId).isEqualTo(testCaseId)
            coVerify(exactly = 1) { adjudication.openOrReuse(any()) }
            coVerify(exactly = 0) { repo.findCandidatesByMatchKey(any()) }
        }

    @Test
    fun `tier-1 — RC collision divergent gender escalates to NEEDS_MANUAL with RN_COLLISION trigger`(): Unit =
        runBlocking {
            val bi = blindIndexOf(validRc)
            // RC says MALE, stored party says FEMALE; tier-2 must NOT run
            val divergentParty = makeParty(birthdate = validRcBirthdate, gender = Gender.FEMALE)
            coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns divergentParty

            val result = svc.resolve(cmd(birthNumberRaw = validRc))

            assertThat(result).isInstanceOf(ResolutionResult.NeedsManualVerification::class.java)
            assertThat((result as ResolutionResult.NeedsManualVerification).trigger)
                .isEqualTo(VerificationTrigger.RN_COLLISION)
            coVerify(exactly = 0) { repo.findCandidatesByMatchKey(any()) }
        }

    @Test
    fun `tier-1 — RC collision against party with null gender is NOT a collision`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        // gender not yet recorded on the stored party → don't block; tier-2 must NOT run
        val partyWithNoGender = makeParty(birthdate = validRcBirthdate, gender = null)
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns partyWithNoGender
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList() // defensive

        val result = svc.resolve(cmd(birthNumberRaw = validRc))

        assertThat(result).isInstanceOf(ResolutionResult.MatchExisting::class.java)
        coVerify(exactly = 0) { repo.findCandidatesByMatchKey(any()) }
    }

    @Test
    fun `tier-1 — invalid RC falls through to tier-2`(): Unit = runBlocking {
        // "123456" is not a valid RČ
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList()

        val result = svc.resolve(cmd(birthNumberRaw = "123456"))

        assertThat(result).isEqualTo(ResolutionResult.NoMatch)
        coVerify(exactly = 1) { repo.findCandidatesByMatchKey(any()) }
    }

    @Test
    fun `tier-1 — missing pepper falls through to tier-2 with warning`(): Unit = runBlocking {
        val svcNoPepper = IdentityResolutionService(repo, adjudication, Optional.empty())
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList()

        val result = svcNoPepper.resolve(cmd(birthNumberRaw = validRc))

        // Tier-1 skipped, tier-2 ran and found nothing
        assertThat(result).isEqualTo(ResolutionResult.NoMatch)
        coVerify(exactly = 0) { repo.findByExternalId(any(), any()) }
        coVerify(exactly = 1) { repo.findCandidatesByMatchKey(any()) }
    }

    // ── tier-2: normalized candidate match ────────────────────────────────────

    @Test
    fun `tier-2 — no RC, zero candidates returns NO_MATCH`(): Unit = runBlocking {
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList()

        val result = svc.resolve(cmd())

        assertThat(result).isEqualTo(ResolutionResult.NoMatch)
    }

    @Test
    fun `tier-2 — namesake candidate returns NEEDS_MANUAL_VERIFICATION with NAMESAKE trigger`(): Unit = runBlocking {
        val namesake = makeParty(
            id = UUID.randomUUID(),
            familyName = "Novak",
            givenName = "Jan",
            birthdate = validRcBirthdate,
            birthplace = null,
        )
        coEvery { repo.findCandidatesByMatchKey(any()) } returns listOf(namesake)

        val result = svc.resolve(cmd())

        assertThat(result).isInstanceOf(ResolutionResult.NeedsManualVerification::class.java)
        val nmv = result as ResolutionResult.NeedsManualVerification
        assertThat(nmv.trigger).isEqualTo(VerificationTrigger.NAMESAKE_CANDIDATE)
        assertThat(nmv.candidates).hasSize(1)
        assertThat(nmv.candidates.first().nameMasked).contains("N.")
        assertThat(nmv.caseId).isEqualTo(testCaseId)
    }

    // ── adjudication cache: a prior human decision steers the next resolve ──────

    @Test
    fun `prior LINK decision turns an RC collision into MATCH_EXISTING`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        val decidedPartyId = UUID.randomUUID()
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns
            makeParty(birthdate = LocalDate.of(1980, 1, 1)) // would otherwise be a collision
        coEvery { adjudication.priorDecision("RN:$bi") } returns
            PriorAdjudication(UUID.randomUUID(), CaseVerdict.LINK_TO_EXISTING, decidedPartyId)

        val result = svc.resolve(cmd(birthNumberRaw = validRc))

        assertThat(result).isInstanceOf(ResolutionResult.MatchExisting::class.java)
        assertThat((result as ResolutionResult.MatchExisting).partyId).isEqualTo(decidedPartyId)
        coVerify(exactly = 0) { adjudication.openOrReuse(any()) } // no new case opened
    }

    @Test
    fun `prior DISTINCT decision turns a namesake into NO_MATCH`(): Unit = runBlocking {
        val namesake = makeParty(id = UUID.randomUUID())
        coEvery { repo.findCandidatesByMatchKey(any()) } returns listOf(namesake)
        coEvery { adjudication.priorDecision(any()) } returns
            PriorAdjudication(UUID.randomUUID(), CaseVerdict.DISTINCT_NEW, null)

        val result = svc.resolve(cmd())

        assertThat(result).isEqualTo(ResolutionResult.NoMatch)
        coVerify(exactly = 0) { adjudication.openOrReuse(any()) }
    }

    // ── tier-2′: probabilistic (Fellegi-Sunter) fuzzy matching ─────────────────

    @Test
    fun `tier-2′ — fuzzy candidate (given-name typo) opens a PROBABILISTIC_CANDIDATE case`(): Unit = runBlocking {
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList() // no exact namesake
        // "Jana" vs applicant "Jan" — one-edit typo; same family + birthdate → high probabilistic weight.
        val fuzzy = makeParty(
            id = UUID.randomUUID(),
            givenName = "Jana",
            familyName = "Novak",
            birthdate = validRcBirthdate,
        )
        coEvery { repo.findCandidatesForProbabilistic(any(), any()) } returns listOf(fuzzy)

        val result = svc.resolve(cmd(givenName = "Jan", familyName = "Novak"))

        assertThat(result).isInstanceOf(ResolutionResult.NeedsManualVerification::class.java)
        val nmv = result as ResolutionResult.NeedsManualVerification
        assertThat(nmv.trigger).isEqualTo(VerificationTrigger.PROBABILISTIC_CANDIDATE)
        assertThat(nmv.caseId).isEqualTo(testCaseId)
        coVerify(exactly = 1) { adjudication.openOrReuse(any()) }
    }

    @Test
    fun `tier-2′ — only NO_MATCH-scoring candidates returns NO_MATCH`(): Unit = runBlocking {
        coEvery { repo.findCandidatesByMatchKey(any()) } returns emptyList()
        // Clearly different person (family + given + year all disagree) → scorer NO_MATCH.
        val different = makeParty(
            id = UUID.randomUUID(),
            givenName = "Petr",
            familyName = "Dvorak",
            birthdate = LocalDate.of(1990, 3, 3),
        )
        coEvery { repo.findCandidatesForProbabilistic(any(), any()) } returns listOf(different)

        val result = svc.resolve(cmd(givenName = "Jan", familyName = "Novak"))

        assertThat(result).isEqualTo(ResolutionResult.NoMatch)
        coVerify(exactly = 0) { adjudication.openOrReuse(any()) }
    }

    // ── resolveByIndex ────────────────────────────────────────────────────────

    @Test
    fun `resolveByIndex returns partyId when BIRTH_NUMBER external-id matches`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        val party = makeParty()
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns party

        val result = svc.resolveByIndex(bi)

        assertThat(result).isEqualTo(existingPartyId)
    }

    @Test
    fun `resolveByIndex returns null when no party matches the blind index`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns null

        val result = svc.resolveByIndex(bi)

        assertThat(result).isNull()
    }

    // ── computeBlindIndex ────────────────────────────────────────────────────

    @Test
    fun `computeBlindIndex returns 64-char hex for a valid RC`() {
        val result = svc.computeBlindIndex(validRc)
        assertThat(result).isNotNull().hasSize(64).matches("[0-9a-f]+")
    }

    @Test
    fun `computeBlindIndex returns null for an invalid RC`() {
        val result = svc.computeBlindIndex("123456")
        assertThat(result).isNull()
    }

    @Test
    fun `computeBlindIndex returns null when pepper is absent`() {
        val svcNoPepper = IdentityResolutionService(repo, adjudication, java.util.Optional.empty())
        val result = svcNoPepper.computeBlindIndex(validRc)
        assertThat(result).isNull()
    }

    @Test
    fun `computeBlindIndex is stable for RC with slash`() {
        val withSlash = "760506/0001"
        val withoutSlash = "7605060001"
        assertThat(svc.computeBlindIndex(withSlash)).isEqualTo(svc.computeBlindIndex(withoutSlash))
    }

    // ── candidate masking ────────────────────────────────────────────────────

    @Test
    fun `candidate summary masks name to initials`(): Unit = runBlocking {
        val bi = blindIndexOf(validRc)
        val party = makeParty(
            birthdate = validRcBirthdate,
            gender = Gender.MALE,
            familyName = "Novotny",
            givenName = "Petr",
        )
        coEvery { repo.findByExternalId(ExternalIdType.BIRTH_NUMBER, bi) } returns null
        coEvery { repo.findCandidatesByMatchKey(any()) } returns listOf(party)

        val result = svc.resolve(cmd(givenName = "Petr", familyName = "Novotny"))

        val nmv = result as ResolutionResult.NeedsManualVerification
        assertThat(nmv.candidates.first().nameMasked).isEqualTo("N. P.")
        assertThat(nmv.candidates.first().birthYear).isEqualTo(validRcBirthdate.year)
    }
}
