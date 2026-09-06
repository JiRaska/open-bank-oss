// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.CaseTransitionException
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.domain.model.SignerStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BusinessOnboardingCaseTest {

    private val now = Instant.parse("2026-09-05T10:00:00Z")
    private val initiator = UUID.randomUUID()
    private val ico = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649")

    private fun extract(
        form: LegalFormClass = LegalFormClass.LIMITED_COMPANY,
        rule: RepresentationRule = RepresentationRule.SOLE,
        reps: List<Representative> = listOf(rep("Jana Nováková"), rep("Petr Svoboda"), rep("Eva Dvořáková")),
        status: EntityStatus = EntityStatus.ACTIVE,
        verification: ExtractVerification = ExtractVerification.VERIFIED,
    ) = RegistryExtract(
        identifier = ico,
        legalName = "Příklad s.r.o.",
        legalFormCode = "112",
        legalFormClass = form,
        status = status,
        registeredAddress = RegisteredAddress("Hlavní 1", "Praha", "11000", "CZ"),
        incorporatedOn = LocalDate.of(2010, 1, 1),
        taxId = "CZ45274649",
        representatives = reps,
        representationRule = rule,
        source = "ares",
        sourceRef = null,
        verification = verification,
        fetchedAt = now,
    )

    private fun rep(name: String) =
        Representative(name, LocalDate.of(1980, 5, 5), "jednatelé", "jednatel", LocalDate.of(2015, 1, 1))

    private fun started() = BusinessOnboardingCase.start(UUID.randomUUID(), ico, initiator, now)

    @Test
    fun `sole trader signs alone and the case is ready right after the initiator is matched`() {
        val case = started()
            .registryVerified(
                extract(LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf(rep("Jan Novák"))),
                now,
            )
            .entityPartyCreated(UUID.randomUUID(), now)
            .initiatorMatched(0, "Jan Novák", null, now)
        assertThat(case.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        assertThat(case.requiredSignatures).isEqualTo(1)
        val signed = case.signed(initiator, "ceremony-1", now)
        assertThat(signed.status).isEqualTo(CaseStatus.SIGNED)
        assertThat(signed.entityPartyActivated(now).status).isEqualTo(CaseStatus.ACTIVE)
    }

    @Test
    fun `two-of-three joint rule needs a verified co-signer before signing opens`() {
        val rule = RepresentationRule(RepresentationMode.JOINT_N, 2, "dva jednatelé společně")
        var case = started().registryVerified(extract(rule = rule), now).entityPartyCreated(UUID.randomUUID(), now)
        assertThat(case.requiredSignatures).isEqualTo(2)
        case = case.initiatorMatched(0, "Jana Nováková", null, now)
        assertThat(case.status).isEqualTo(CaseStatus.INITIATOR_MATCHED)

        assertThatThrownBy { case.cosignersInvited(emptyList(), emptyList(), now) }
            .isInstanceOf(CaseTransitionException::class.java)
            .hasMessageContaining("needs 2 signers")

        case = case.cosignersInvited(listOf(1, 0), listOf("tok-1"), now) // the initiator's own index is ignored
        assertThat(case.status).isEqualTo(CaseStatus.AWAITING_COSIGNERS)
        assertThat(case.signers).hasSize(2)
        val invited = case.signers.first { !it.isInitiator }
        assertThat(invited.status).isEqualTo(SignerStatus.INVITED)
        assertThat(invited.invitationToken).isEqualTo("tok-1")

        // Signing before the co-signer is identified is allowed for the initiator but does not complete.
        val cosigner = UUID.randomUUID()
        case = case.signed(initiator, "ceremony-a", now)
        assertThat(case.status).isEqualTo(CaseStatus.AWAITING_COSIGNERS)

        case = case.signerIdentified("tok-1", cosigner, now)
        assertThat(case.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        assertThatThrownBy {
            case.signerIdentified("tok-1", UUID.randomUUID(), now)
        }.isInstanceOf(CaseTransitionException::class.java)

        case = case.signed(cosigner, "ceremony-b", now)
        assertThat(case.status).isEqualTo(CaseStatus.SIGNED)
        assertThat(case.signedCount).isEqualTo(2)
    }

    @Test
    fun `an initiator who is not a listed representative goes to manual review with the claim recorded`() {
        val case = started().registryVerified(
            extract(),
            now,
        ).initiatorMatched(null, "Karel Cizí", LocalDate.of(1990, 2, 2), now)
        assertThat(case.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(case.reviewReason).contains("power of attorney")
        assertThat(case.initiator?.partyId).isEqualTo(initiator)

        val resolved = case.reviewResolved(1, now)
        assertThat(resolved.status).isEqualTo(CaseStatus.READY_TO_SIGN)
    }

    @Test
    fun `an unparseable rule, an unverified extract or a dissolved entity all route to review`() {
        assertThat(
            started().registryVerified(extract(rule = RepresentationRule.UNKNOWN), now).status,
        ).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(
            started().registryVerified(extract(verification = ExtractVerification.UNVERIFIED), now).status,
        ).isEqualTo(CaseStatus.MANUAL_REVIEW)
        val dissolved = started().registryVerified(extract(status = EntityStatus.DISSOLVED), now)
        assertThat(dissolved.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(dissolved.reviewReason).contains("DISSOLVED")
    }

    @Test
    fun `the entity party may activate before the last signature and the case completes on signing`() {
        val case = started().registryVerified(
            extract(LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf(rep("Jan Novák"))),
            now,
        )
            .entityPartyCreated(UUID.randomUUID(), now)
            .initiatorMatched(0, "Jan Novák", null, now)
            .entityPartyActivated(now)
        assertThat(case.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        assertThat(case.entityPartyActive).isTrue()
        assertThat(case.signed(initiator, "c", now).status).isEqualTo(CaseStatus.ACTIVE)
    }

    @Test
    fun `a stranger cannot sign and a terminal case cannot move`() {
        val case = started().registryVerified(
            extract(LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf(rep("Jan Novák"))),
            now,
        )
            .initiatorMatched(0, "Jan Novák", null, now)
        assertThatThrownBy {
            case.signed(UUID.randomUUID(), "x", now)
        }.isInstanceOf(CaseTransitionException::class.java)
        val abandoned = case.abandoned(now)
        assertThatThrownBy { abandoned.rejected("no", now) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
