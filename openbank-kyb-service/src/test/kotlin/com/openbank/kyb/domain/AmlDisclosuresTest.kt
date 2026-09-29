// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain

import com.openbank.kyb.domain.model.AcceptedDisclosure
import com.openbank.kyb.domain.model.AgreementConflictException
import com.openbank.kyb.domain.model.AgreementRecord
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.CaseTransitionException
import com.openbank.kyb.domain.model.CrsStatus
import com.openbank.kyb.domain.model.Declarations
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExpectedTurnover
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.FatcaStatus
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.InitiatorIdentity
import com.openbank.kyb.domain.model.KnownPerson
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.PepEntry
import com.openbank.kyb.domain.model.Questionnaire
import com.openbank.kyb.domain.model.RegisteredAddress
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RelationshipPurpose
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.kyb.domain.model.SourceOfFunds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AmlDisclosuresTest {

    private val now = Instant.parse("2026-09-17T10:00:00Z")
    private val me: UUID = UUID.randomUUID()
    private val ico = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "45274649")
    private val highRisk = setOf("IR", "KP")

    private val q = Questionnaire(
        purpose = RelationshipPurpose.OPERATING_ACCOUNT,
        expectedMonthlyTurnover = ExpectedTurnover.UP_TO_1M,
        sourceOfFunds = setOf(SourceOfFunds.BUSINESS_REVENUE),
        cashIntensive = false,
        countries = listOf("CZ", "DE"),
        taxResidencies = listOf("CZ"),
        fatcaStatus = FatcaStatus.ACTIVE_NFFE,
        crsStatus = CrsStatus.ACTIVE_NFE,
    )

    private val d = Declarations(
        uboConfirmed = true,
        peps = listOf(PepEntry("Jana Nováková", false)),
        truthful = true,
    )

    private val record = AgreementRecord(
        documentId = UUID.randomUUID(),
        ceremonyId = UUID.randomUUID(),
        templateCode = "RAMCOVA_SMLOUVA_PO_CS",
        templateVersion = "1.0.0",
        sha256 = "a".repeat(64),
        lang = "cs",
    )

    private val disclosures = listOf(
        AcceptedDisclosure("VOP_CS", "1.1.0", "b".repeat(64)),
        AcceptedDisclosure("SAZEBNIK_PO_CS", "1.0.0", "c".repeat(64)),
    )

    /** A SOLE s.r.o. whose single jednatel is the initiator, ready to sign. */
    private fun ready(): BusinessOnboardingCase {
        val ex = RegistryExtract(
            identifier = ico,
            legalName = "Příklad s.r.o.",
            legalFormCode = "112",
            legalFormClass = LegalFormClass.LIMITED_COMPANY,
            status = EntityStatus.ACTIVE,
            registeredAddress = RegisteredAddress("Hlavní 1", "Praha", "11000", "CZ"),
            incorporatedOn = LocalDate.of(2010, 1, 1),
            taxId = null,
            representatives = listOf(
                Representative("Ing. Jana Nováková", LocalDate.of(1980, 5, 5), "jednatelé", "jednatel", null),
            ),
            representationRule = RepresentationRule(RepresentationMode.SOLE, 1, "jednatel samostatně"),
            source = "ares",
            sourceRef = null,
            verification = ExtractVerification.VERIFIED,
            fetchedAt = now,
        )
        val decision = RepresentationDecision.Attested(
            RepresentationAttestation(
                id = UUID.randomUUID(),
                identifier = ico,
                ruleTextHash = RepresentationAttestation.hashOf(ex.representationRule.sourceText),
                ruleText = ex.representationRule.sourceText,
                parsedMode = RepresentationMode.SOLE,
                parsedSigners = 1,
                confirmedSigners = 1,
                confirmedRoles = emptyList(),
                attestedBy = "operator",
                attestedAt = now,
            ),
        )
        val case = BusinessOnboardingCase.start(UUID.randomUUID(), ico, me, now)
            .registryVerified(ex, decision, now)
            .entityPartyCreated(UUID.randomUUID(), now)
            .initiatorMatched(0, InitiatorIdentity("Jana Nováková", null, true), now)
        assertThat(case.status).isEqualTo(CaseStatus.READY_TO_SIGN)
        return case
    }

    private fun answered(qq: Questionnaire = q, dd: Declarations = d, known: List<KnownPerson> = emptyList()) =
        ready().questionnaireAnswered(qq, me, now).declarationsMade(dd, emptyList(), known, me, now)

    private fun BusinessOnboardingCase.accepted() = agreementPrepared(record, now)
        .disclosuresAccepted(disclosures, disclosures, me, now)

    // --- questionnaire validation -----------------------------------------------------------

    @Test
    fun `a complete questionnaire is stored with who answered and when`() {
        val case = ready().questionnaireAnswered(q, me, now)
        assertThat(case.questionnaire!!.answeredBy).isEqualTo(me)
        assertThat(case.questionnaire!!.answeredAt).isEqualTo(now)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQuestionnaires")
    fun `an invalid questionnaire is a 400`(label: String, bad: (Questionnaire) -> Questionnaire) {
        assertThatThrownBy { ready().questionnaireAnswered(bad(q), me, now) }
            .describedAs(label)
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the questionnaire is closed outside the collecting states and after a signature`() {
        assertThatThrownBy {
            BusinessOnboardingCase.start(UUID.randomUUID(), ico, me, now).questionnaireAnswered(q, me, now)
        }.isInstanceOf(CaseTransitionException::class.java)
        val signedOnce = answered().accepted().let {
            // a JOINT-looking case where one signature is in: requiredSignatures raised so it stays open
            it.copy(requiredSignatures = 2).signed(me, record.ceremonyId.toString(), now)
        }
        assertThat(signedOnce.signedCount).isEqualTo(1)
        assertThatThrownBy { signedOnce.questionnaireAnswered(q, me, now) }
            .isInstanceOf(CaseTransitionException::class.java)
    }

    // --- declarations validation ------------------------------------------------------------

    @Test
    fun `truthful must be confirmed`() {
        assertThatThrownBy { answered(dd = d.copy(truthful = false)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("truthful")
    }

    @Test
    fun `a UBO discrepancy needs a note`() {
        assertThatThrownBy { answered(dd = d.copy(uboConfirmed = false)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("uboDiscrepancyNote")
    }

    @Test
    fun `a PEP entry needs its function`() {
        assertThatThrownBy { answered(dd = d.copy(peps = listOf(PepEntry("Jana Nováková", true)))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("detail")
    }

    @Test
    fun `every listed representative and UBO must be covered, whatever titles or word order`() {
        assertThatThrownBy { answered(dd = d.copy(peps = emptyList())) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Jana Nováková")
        // "Nováková Jana" covers the register's "Ing. Jana Nováková".
        answered(dd = d.copy(peps = listOf(PepEntry("Nováková Jana", false))))
        assertThatThrownBy {
            ready().questionnaireAnswered(q, me, now)
                .declarationsMade(d, listOf("Karel Vlastník"), emptyList(), me, now)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Karel Vlastník")
    }

    @Test
    fun `a person whose PEP status is on file need not be declared, and a contradiction is kept out`() {
        val known = listOf(KnownPerson("Jana Nováková", me, pep = false, pepCategory = null))
        val none = answered(dd = d.copy(peps = emptyList()), known = known)
        assertThat(none.declarations!!.peps.single().partyId).isEqualTo(me)
        assertThat(none.riskFlags(highRisk)).isEmpty()

        val unknown = listOf(KnownPerson("Jana Nováková", me, pep = null, pepCategory = null))
        assertThatThrownBy { answered(dd = d.copy(peps = emptyList()), known = unknown) }
            .describedAs("no profile fact means the person must declare")
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- risk flags: one test per flag, plus the clean path ---------------------------------

    @Test
    fun `no flag - the clean questionnaire and declarations raise nothing`() {
        assertThat(answered().riskFlags(highRisk)).isEmpty()
    }

    @Test
    fun `flag - a declared PEP`() {
        val flags = answered(
            dd = d.copy(peps = listOf(PepEntry("Jana Nováková", true, "starostka"))),
        ).riskFlags(highRisk)
        assertThat(flags).containsExactly("PEP declared: Jana Nováková (starostka)")
    }

    @Test
    fun `flag - a PEP on the customer profile`() {
        val known = listOf(KnownPerson("Jana Nováková", me, pep = true, pepCategory = "DOMESTIC"))
        assertThat(answered(dd = d.copy(peps = emptyList()), known = known).riskFlags(highRisk))
            .containsExactly("PEP declared: Jana Nováková (DOMESTIC)")
    }

    @Test
    fun `flag - a declaration contradicting the customer profile`() {
        val known = listOf(KnownPerson("Jana Nováková", me, pep = false, pepCategory = null))
        val case = answered(dd = d.copy(peps = listOf(PepEntry("Jana Nováková", true, "poslankyně"))), known = known)
        assertThat(case.declarations!!.peps.single().isPep).describedAs("the profile wins").isFalse()
        assertThat(case.riskFlags(highRisk))
            .containsExactly("PEP declaration differs from customer profile: Jana Nováková")
    }

    @Test
    fun `flag - FATCA status other than an active NFFE`() {
        FatcaStatus.entries.filter { it != FatcaStatus.ACTIVE_NFFE }.forEach { status ->
            assertThat(answered(qq = q.copy(fatcaStatus = status)).riskFlags(highRisk))
                .containsExactly("FATCA status $status")
        }
    }

    @Test
    fun `flag - a tax residency outside the entity's country`() {
        assertThat(answered(qq = q.copy(taxResidencies = listOf("CZ", "CY"))).riskFlags(highRisk))
            .containsExactly("tax residency outside CZ: CY")
    }

    @Test
    fun `flag - a cash-intensive business`() {
        assertThat(answered(qq = q.copy(cashIntensive = true)).riskFlags(highRisk))
            .containsExactly("cash-intensive business")
    }

    @Test
    fun `flag - a high-risk country`() {
        assertThat(answered(qq = q.copy(countries = listOf("DE", "IR"))).riskFlags(highRisk))
            .containsExactly("high-risk countries: IR")
    }

    @Test
    fun `flag - a UBO discrepancy`() {
        assertThat(
            answered(dd = d.copy(uboConfirmed = false, uboDiscrepancyNote = "missing Karel")).riskFlags(highRisk),
        )
            .containsExactly("beneficial-owner discrepancy reported: missing Karel")
    }

    // --- agreement, acceptance, signature ---------------------------------------------------

    @Test
    fun `the agreement needs the questionnaire and the declarations`() {
        val e = runCatching { ready().agreementPrepared(record, now) }.exceptionOrNull()
        assertThat((e as AgreementConflictException).code).isEqualTo(AgreementConflictException.PREREQUISITES_MISSING)
    }

    @Test
    fun `a re-rendered agreement starts acceptance over, the same one keeps it`() {
        val accepted = answered().accepted()
        assertThat(accepted.agreementPrepared(record, now).agreement!!.acceptedByParty(me)).isTrue()
        val rerendered = accepted.agreementPrepared(record.copy(ceremonyId = UUID.randomUUID()), now)
        assertThat(rerendered.agreement!!.acceptedByParty(me)).isFalse()
    }

    @Test
    fun `signing is refused without an agreement, without acceptance and with a foreign ref`() {
        val case = answered()
        fun code(block: () -> Unit) = (runCatching(block).exceptionOrNull() as AgreementConflictException).code
        assertThat(code { case.signed(me, record.ceremonyId.toString(), now) })
            .isEqualTo(AgreementConflictException.NOT_PREPARED)
        val prepared = case.agreementPrepared(record, now)
        assertThat(code { prepared.signed(me, record.ceremonyId.toString(), now) })
            .isEqualTo(AgreementConflictException.DISCLOSURES_NOT_ACCEPTED)
        val accepted = prepared.disclosuresAccepted(disclosures, disclosures, me, now)
        assertThat(code { accepted.signed(me, "cer-1", now) })
            .isEqualTo(AgreementConflictException.SIGNATURE_REF_MISMATCH)
        assertThat(accepted.signed(me, record.ceremonyId.toString(), now).status).isEqualTo(CaseStatus.SIGNED)
    }

    @Test
    fun `a stale disclosure set is refused`() {
        val prepared = answered().agreementPrepared(record, now)
        val e = runCatching { prepared.disclosuresAccepted(disclosures.take(1), disclosures, me, now) }
        assertThat((e.exceptionOrNull() as AgreementConflictException).code)
            .isEqualTo(AgreementConflictException.DISCLOSURES_STALE)
    }

    @Test
    fun `a risk flag routes the fully signed case to MANUAL_REVIEW naming every flag`() {
        val case = answered(qq = q.copy(cashIntensive = true, countries = listOf("KP"))).accepted()
        val signed = case.signed(me, record.ceremonyId.toString(), now, highRisk)
        assertThat(signed.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(signed.signers.single().status).isEqualTo(SignerStatus.SIGNED)
        assertThat(signed.reviewReason)
            .startsWith("AML risk review before activation:")
            .contains("cash-intensive business")
            .contains("high-risk countries: KP")
        // The reviewer accepts: the signatures stand and the case completes.
        assertThat(signed.reviewResolved(1, now).status).isEqualTo(CaseStatus.SIGNED)
    }

    companion object {
        @JvmStatic
        fun invalidQuestionnaires(): List<Array<Any>> = listOf<Pair<String, (Questionnaire) -> Questionnaire>>(
            "OTHER purpose without a note" to { it.copy(purpose = RelationshipPurpose.OTHER) },
            "no source of funds" to { it.copy(sourceOfFunds = emptySet()) },
            "OTHER source without a note" to { it.copy(sourceOfFunds = setOf(SourceOfFunds.OTHER)) },
            "no countries" to { it.copy(countries = emptyList()) },
            "no tax residency" to { it.copy(taxResidencies = emptyList()) },
            "lower-case country" to { it.copy(countries = listOf("cz")) },
            "alpha-3 tax residency" to { it.copy(taxResidencies = listOf("CZE")) },
            "overlong note" to {
                it.copy(purpose = RelationshipPurpose.OTHER, purposeNote = "x".repeat(Questionnaire.MAX_NOTE + 1))
            },
        ).map { arrayOf(it.first, it.second) }
    }
}
