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
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
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

    /**
     * An operator has confirmed this extract's rule (#9711). Most tests below are about the signing
     * mechanics that follow, not about the confirmation itself, so they use this; the tests that ARE
     * about the confirmation pass their own decision explicitly.
     */
    private fun attested(ex: RegistryExtract, signers: Int? = null, roles: List<String> = emptyList()) =
        RepresentationDecision.Attested(
            RepresentationAttestation(
                id = UUID.randomUUID(),
                identifier = ico,
                ruleTextHash = RepresentationAttestation.hashOf(ex.representationRule.sourceText),
                ruleText = ex.representationRule.sourceText,
                parsedMode = ex.representationRule.mode,
                parsedSigners = ex.representationRule.requiredSigners,
                confirmedSigners = signers ?: ex.representationRule.requiredSigners ?: 1,
                confirmedRoles = roles,
                attestedBy = "operator-anna",
                attestedAt = now,
            ),
        )

    private fun BusinessOnboardingCase.registryVerifiedAttested(ex: RegistryExtract) =
        registryVerified(ex, attested(ex), now)

    private fun unattested(ex: RegistryExtract) = RepresentationDecision.Unattested(ex.representationRule)

    private fun started() = BusinessOnboardingCase.start(UUID.randomUUID(), ico, initiator, now)

    @Test
    fun `sole trader signs alone and the case is ready right after the initiator is matched`() {
        val case = started()
            .registryVerifiedAttested(
                extract(LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf(rep("Jan Novák"))),
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
        var case = started().registryVerifiedAttested(extract(rule = rule)).entityPartyCreated(UUID.randomUUID(), now)
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
        val case = started().registryVerifiedAttested(
            extract(),
        ).initiatorMatched(null, "Karel Cizí", LocalDate.of(1990, 2, 2), now)
        assertThat(case.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(case.reviewReason).contains("power of attorney")
        assertThat(case.initiator?.partyId).isEqualTo(initiator)

        val resolved = case.reviewResolved(1, now)
        assertThat(resolved.status).isEqualTo(CaseStatus.READY_TO_SIGN)
    }

    @Test
    fun `an unparseable rule, an unverified extract or a dissolved entity all route to review`() {
        val unparseable = extract(rule = RepresentationRule.UNKNOWN)
        assertThat(
            started().registryVerified(unparseable, unattested(unparseable), now).status,
        ).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(
            started().registryVerifiedAttested(extract(verification = ExtractVerification.UNVERIFIED)).status,
        ).isEqualTo(CaseStatus.MANUAL_REVIEW)
        val dissolved = started().registryVerifiedAttested(extract(status = EntityStatus.DISSOLVED))
        assertThat(dissolved.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(dissolved.reviewReason).contains("DISSOLVED")
    }

    @Test
    fun `the entity party may activate before the last signature and the case completes on signing`() {
        val case = started().registryVerifiedAttested(
            extract(LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf(rep("Jan Novák"))),
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
        val case = started().registryVerifiedAttested(
            extract(LegalFormClass.SOLE_TRADER, RepresentationRule.SOLE, listOf(rep("Jan Novák"))),
        )
            .initiatorMatched(0, "Jan Novák", null, now)
        assertThatThrownBy {
            case.signed(UUID.randomUUID(), "x", now)
        }.isInstanceOf(CaseTransitionException::class.java)
        val abandoned = case.abandoned(now)
        assertThatThrownBy { abandoned.rejected("no", now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the PARSER alone can never make a case proceed — only a human confirmation can`() {
        // The whole point of #9711. The rule here parses cleanly and confidently: SOLE, one
        // signature, no ambiguity a heuristic could flag. It still does not bind, because nobody
        // has confirmed it. Delete the Unattested branch and this case walks to REGISTRY_VERIFIED
        // on a regex's opinion.
        val confident = extract(rule = RepresentationRule(RepresentationMode.SOLE, 1, "Jednatel jedná samostatně."))

        val case = started().registryVerified(confident, unattested(confident), now)

        assertThat(case.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(case.requiredSignatures)
            .describedAs("no count may be carried forward from an unconfirmed rule")
            .isNull()
        assertThat(case.reviewReason).contains("awaits confirmation").contains("parser suggests")
    }

    @Test
    fun `a rule that CHANGED since it was confirmed reviews, and the reviewer is told what changed`() {
        // The amendment case — the single event this control exists for, because it is exactly when
        // yesterday's signature count becomes wrong while the IČO stays the same. Answering
        // "unattested" here would be safe but silent; the reviewer would see a blank form for a
        // company they recognise and confirm from memory.
        val old = RepresentationAttestation(
            id = UUID.randomUUID(),
            identifier = ico,
            ruleTextHash = RepresentationAttestation.hashOf("Jednatel jedná samostatně."),
            ruleText = "Jednatel jedná samostatně.",
            parsedMode = RepresentationMode.SOLE,
            parsedSigners = 1,
            confirmedSigners = 1,
            confirmedRoles = emptyList(),
            attestedBy = "operator-anna",
            attestedAt = now,
        )
        val amended = extract(
            rule = RepresentationRule(RepresentationMode.JOINT_N, 2, "Jednají vždy dva jednatelé společně."),
        )

        val case = started().registryVerified(
            amended,
            RepresentationDecision.Superseded(old, amended.representationRule),
            now,
        )

        assertThat(case.status).isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(case.requiredSignatures).isNull()
        assertThat(case.reviewReason)
            .describedAs("a reviewer who is not told the rule moved will re-confirm the old one")
            .contains("CHANGED")
            .contains("operator-anna")
            .contains("dva jednatelé")
    }

    @Test
    fun `an identical rule text confirmed once proceeds on the HUMAN number, not the parser's`() {
        // The operator overrode the parser: the text reads solo to the regex, the human read the
        // rest of the document and said two. The case must use two.
        val ex = extract(rule = RepresentationRule(RepresentationMode.SOLE, 1, "Jednatel jedná samostatně."))

        val case = started().registryVerified(ex, attested(ex, signers = 2), now)

        assertThat(case.status).isEqualTo(CaseStatus.REGISTRY_VERIFIED)
        assertThat(case.requiredSignatures).isEqualTo(2)
    }

    @Test
    fun `a confirmed rule naming offices needs those OFFICES filled, not just the right count`() {
        // Kofola's shape. Two ordinary members meet a count of two and do not meet the rule; the
        // office list is the only thing that can tell them apart.
        val reps = listOf(
            rep("Jana Nováková").copy(role = "předseda představenstva"),
            rep("Petr Svoboda").copy(role = "člen představenstva"),
            rep("Eva Dvořáková").copy(role = "člen představenstva"),
        )
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(
                RepresentationMode.JOINT_N,
                2,
                "Předseda představenstva spolu s jedním členem představenstva.",
                requiredRoles = listOf("predseda", "clen"),
            ),
        )
        var case = started()
            .registryVerified(ex, attested(ex, signers = 2, roles = listOf("predseda", "clen")), now)
            .entityPartyCreated(UUID.randomUUID(), now)
        assertThat(case.requiredSignerRoles).containsExactly("predseda", "clen")

        // The two ordinary members are the right NUMBER and the wrong PEOPLE.
        val twoMembers = case.initiatorMatched(1, "Petr Svoboda", null, now)
        assertThatThrownBy { twoMembers.cosignersInvited(listOf(2), listOf("tok-1"), now) }
            .isInstanceOf(CaseTransitionException::class.java)
            .hasMessageContaining("predseda")

        // The chair plus one member is accepted.
        case = case.initiatorMatched(0, "Jana Nováková", null, now)
        val ok = case.cosignersInvited(listOf(1), listOf("tok-1"), now)
        assertThat(ok.status).isEqualTo(CaseStatus.AWAITING_COSIGNERS)
        assertThat(ok.signers).hasSize(2)
    }

    @Test
    fun `one person holding both offices cannot fill them both — the count is met and the rule is not`() {
        // The distinctness half of the office check, and it needs the COUNT to be satisfied or the
        // size check upstream catches the case first and this proves nothing. Two signers are
        // selected, so `all.size >= required` passes; the defect is that one of them carries both
        // office words in their register role, so a "does some signer match each office" test is
        // happy while only ONE person's signature is actually required by that reading.
        val reps = listOf(
            rep("Jan Dvojrole").copy(role = "předseda představenstva a člen představenstva"),
            rep("Petr Prokura").copy(role = "prokurista"),
        )
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(
                RepresentationMode.JOINT_N,
                2,
                "Předseda spolu s členem představenstva.",
                requiredRoles = listOf("predseda", "clen"),
            ),
        )
        val case = started()
            .registryVerified(ex, attested(ex, signers = 2, roles = listOf("predseda", "clen")), now)
            .initiatorMatched(0, "Jan Dvojrole", null, now)

        assertThatThrownBy { case.cosignersInvited(listOf(1), listOf("tok-1"), now) }
            .describedAs("two people signed, and nobody holds the second office — a title is not a second signature")
            .isInstanceOf(CaseTransitionException::class.java)
            .hasMessageContaining("clen")
    }

    @Test
    fun `a ONE-signature rule that names the office still checks the initiator holds it`() {
        // A single-signature office rule never invites anyone, so it would skip the office check in
        // `cosignersInvited` entirely and go straight to READY_TO_SIGN on the wrong person.
        val reps = listOf(rep("Jana Nováková").copy(role = "předseda představenstva"), rep("Petr Svoboda"))
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(
                RepresentationMode.SOLE,
                1,
                "Jedná předseda představenstva.",
                requiredRoles = listOf("predseda"),
            ),
        )
        val verified = started().registryVerified(ex, attested(ex, signers = 1, roles = listOf("predseda")), now)

        assertThat(verified.initiatorMatched(0, "Jana Nováková", null, now).status)
            .isEqualTo(CaseStatus.READY_TO_SIGN)

        val wrongPerson = verified.initiatorMatched(1, "Petr Svoboda", null, now)
        assertThat(wrongPerson.status)
            .describedAs("a jednatel is not the chair the rule names")
            .isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(wrongPerson.reviewReason).contains("predseda")
    }

    @Test
    fun `over-inviting cannot let the WRONG pair sign — the offices are re-checked at signature`() {
        // The hole this closes. cosignersInvited permits more invitees than the rule needs, so the
        // people who actually turn up are a subset of the people who were checked — and a subset
        // that reaches the COUNT can miss an office. Chair + vice is the rule; chair + ordinary
        // member is what signs.
        val reps = listOf(
            rep("Jana Chairová").copy(role = "předseda představenstva"),
            rep("Viktor Vice").copy(role = "místopředseda představenstva"),
            rep("Milan Member").copy(role = "člen představenstva"),
        )
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(
                RepresentationMode.JOINT_N,
                2,
                "předseda spolu s místopředsedou",
                requiredRoles = listOf("predseda", "mistopredseda"),
            ),
        )
        var case = started()
            .registryVerified(ex, attested(ex, signers = 2, roles = listOf("predseda", "mistopredseda")), now)
            .entityPartyCreated(UUID.randomUUID(), now)
            .initiatorMatched(0, "Jana Chairová", null, now)

        // Inviting both the vice and the member passes: the invited SET covers the offices.
        case = case.cosignersInvited(listOf(1, 2), listOf("tok-vice", "tok-member"), now)

        // Only the member turns up. The count is reached, so signing must NOT open on it alone.
        case = case.signerIdentified("tok-member", UUID.randomUUID(), now)
        assertThat(case.status)
            .describedAs("two identified people reach the count and do not cover predseda + mistopredseda")
            .isEqualTo(CaseStatus.AWAITING_COSIGNERS)

        // And even if it somehow did, the terminal transition refuses the wrong pair.
        val forced = case.copy(status = CaseStatus.READY_TO_SIGN)
        val afterChair = forced.signed(initiator, "ceremony-chair", now)
        val member = forced.signers.first { it.fullName == "Milan Member" }.partyId!!
        val done = afterChair.signed(member, "ceremony-member", now)

        assertThat(done.status)
            .describedAs("chair + ordinary member must never bind a chair + vice-chair rule")
            .isEqualTo(CaseStatus.MANUAL_REVIEW)
        assertThat(done.reviewReason).contains("do not cover")
    }

    @Test
    fun `mistopredseda does not satisfy predseda — the office must BEGIN a word`() {
        // fold("místopředseda představenstva") CONTAINS "predseda". A substring test therefore
        // let two vice-chairs — and boards routinely have more than one — satisfy a chair + vice
        // rule with no chair anywhere on the agreement.
        val reps = listOf(
            rep("Viktor Vice").copy(role = "místopředseda představenstva"),
            rep("Vilma Vice").copy(role = "místopředseda představenstva"),
        )
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(
                RepresentationMode.JOINT_N,
                2,
                "předseda spolu s místopředsedou",
                requiredRoles = listOf("predseda", "mistopredseda"),
            ),
        )
        val case = started()
            .registryVerified(ex, attested(ex, signers = 2, roles = listOf("predseda", "mistopredseda")), now)
            .initiatorMatched(0, "Viktor Vice", null, now)

        assertThatThrownBy { case.cosignersInvited(listOf(1), listOf("tok-1"), now) }
            .isInstanceOf(CaseTransitionException::class.java)
            .hasMessageContaining("predseda")
    }

    @Test
    fun `a coverable set is never refused because of the order the signers happen to be listed in`() {
        // The other half of the same defect: greedy first-fit. With the vice listed first, an
        // assignment that let `predseda` take him would then find no one for `mistopredseda` and
        // reject a genuine chair + vice pair.
        val reps = listOf(
            rep("Viktor Vice").copy(role = "místopředseda představenstva"),
            rep("Jana Chairová").copy(role = "předseda představenstva"),
        )
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(
                RepresentationMode.JOINT_N,
                2,
                "předseda spolu s místopředsedou",
                requiredRoles = listOf("predseda", "mistopredseda"),
            ),
        )
        val case = started()
            .registryVerified(ex, attested(ex, signers = 2, roles = listOf("predseda", "mistopredseda")), now)
            .initiatorMatched(0, "Viktor Vice", null, now)

        assertThat(case.cosignersInvited(listOf(1), listOf("tok-1"), now).status)
            .isEqualTo(CaseStatus.AWAITING_COSIGNERS)
    }

    @Test
    fun `resolving a review keeps the office constraint enforceable instead of merely recording it`() {
        // reviewResolved SET requiredSignerRoles and nothing enforced them: the case went
        // INITIATOR_MATCHED -> recomputeReadiness, which compared counts only. So a one-signature
        // office rule could be completed by whoever happened to be the initiator.
        val reps = listOf(
            rep("Milan Member").copy(role = "člen představenstva"),
            rep("Jana Chairová").copy(role = "předseda představenstva"),
        )
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(RepresentationMode.SOLE, 1, "jedná předseda", requiredRoles = listOf("predseda")),
        )
        val inReview = started()
            .registryVerified(ex, attested(ex, signers = 1, roles = listOf("predseda")), now)
            .initiatorMatched(0, "Milan Member", null, now)
        assertThat(inReview.status).isEqualTo(CaseStatus.MANUAL_REVIEW)

        // The operator re-supplies the office. An ordinary member must still not become ready.
        val resolved = inReview.reviewResolved(1, now, listOf("predseda"))
        assertThat(resolved.status)
            .describedAs("the member does not hold the office the operator just re-stated")
            .isNotEqualTo(CaseStatus.READY_TO_SIGN)
    }

    @Test
    fun `a resolved review stores the office normalised, so the console shows one constraint not two`() {
        // Matching is insensitive to case and diacritics either way (holdsOffice folds), so this is
        // about what is PERSISTED and echoed in CaseResponse — an operator's "Předseda " and a
        // colleague's "predseda" must not read as two different constraints.
        val ex = extract(
            reps = listOf(rep("Jana Chairová").copy(role = "předseda představenstva")),
            rule = RepresentationRule(RepresentationMode.SOLE, 1, "jedná předseda"),
        )
        val inReview = started().registryVerified(ex, unattested(ex), now)

        val resolved = inReview.reviewResolved(1, now, listOf("  Předseda  ", "", "   "))

        assertThat(resolved.requiredSignerRoles).containsExactly("predseda")
    }

    @Test
    fun `an office typed with Czech capitals and diacritics still matches the register wording`() {
        // The signer side is folded; the operator side was only trimmed. "Předseda" would have
        // produced an office that could never match "předseda představenstva".
        val reps = listOf(rep("Jana Chairová").copy(role = "předseda představenstva"), rep("Petr Svoboda"))
        val ex = extract(
            reps = reps,
            rule = RepresentationRule(RepresentationMode.SOLE, 1, "jedná předseda", requiredRoles = listOf("predseda")),
        )

        val case = started()
            .registryVerified(ex, attested(ex, signers = 1, roles = listOf("Předseda")), now)
            .initiatorMatched(0, "Jana Chairová", null, now)

        assertThat(case.status).isEqualTo(CaseStatus.READY_TO_SIGN)
    }
}
