// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser
import com.openbank.libs.domain.identifiers.Ids
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class CaseStatus {
    IDENTIFIER_ENTERED,
    REGISTRY_VERIFIED,
    INITIATOR_MATCHED,
    AWAITING_COSIGNERS,
    READY_TO_SIGN,
    SIGNED,
    ACTIVE,
    MANUAL_REVIEW,
    REJECTED,
    ABANDONED,
    ;

    val isTerminal: Boolean get() = this == ACTIVE || this == REJECTED || this == ABANDONED
}

enum class SignerStatus { INVITED, IDENTIFIED, SIGNED, DECLINED }

/**
 * One person who must sign the framework agreement for the entity. The initiator is a signer
 * too. [partyId] is null until the person has onboarded AS THEMSELVES and verified their identity
 * — an invitation token is never a credential, it only ties the later individual onboarding back
 * to this case.
 */
data class Signer(
    val id: UUID,
    /** Index into [BusinessOnboardingCase.extract]`.representatives`, or null for a manually added signer. */
    val representativeIndex: Int?,
    val fullName: String,
    val dateOfBirth: LocalDate?,
    val partyId: UUID?,
    val status: SignerStatus,
    val invitationToken: String?,
    val invitedAt: Instant?,
    val identifiedAt: Instant?,
    val signedAt: Instant?,
    val signatureRef: String?,
    val isInitiator: Boolean,
)

class CaseTransitionException(message: String) : IllegalStateException(message)

/**
 * The multi-party business onboarding aggregate (ADR-0284 D1). Every transition is a pure function
 * returning a new copy; the use case persists it together with the event it produced.
 */
@Suppress("TooManyFunctions") // one transition per method; the count belongs to the state machine
data class BusinessOnboardingCase(
    val id: UUID,
    val identifier: LegalEntityIdentifier,
    val initiatorPartyId: UUID,
    val status: CaseStatus,
    val extract: RegistryExtract?,
    val entityPartyId: UUID?,
    val requiredSignatures: Int?,
    /**
     * The offices the attested rule requires, when it names them (#9711). Empty means the rule is a
     * plain count and any listed representatives may sign; non-empty means [cosignersInvited] must
     * be able to cover every office from the chosen signers' register roles.
     */
    val requiredSignerRoles: List<String> = emptyList(),
    val signers: List<Signer>,
    val reviewReason: String?,
    /** The entity party has passed the KYC + AML gate (ADR-0267) — may arrive before or after the last signature. */
    val entityPartyActive: Boolean = false,
    /** AML / FATCA / CRS answers; null until the customer answered them. */
    val questionnaire: Questionnaire? = null,
    /** UBO confirmation, PEP and truthfulness declarations; null until made. */
    val declarations: Declarations? = null,
    /** The rendered framework agreement and its signature ceremony; null until prepared. */
    val agreement: AgreementRecord? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {

    val initiator: Signer? get() = signers.firstOrNull { it.isInitiator }

    val signedCount: Int get() = signers.count { it.status == SignerStatus.SIGNED }

    private val signedSigners: List<Signer> get() = signers.filter { it.status == SignerStatus.SIGNED }

    /**
     * The register record has been fetched. Decides between the automatic path and manual review.
     *
     * [decision] is what the attestation store says about THIS entity's current rule text (#9711).
     * The parser's own verdict never reaches [requiredSignatures] any more: it is a suggestion the
     * operator sees, and only [RepresentationDecision.Attested] — a confirmation of this exact text —
     * lets a case proceed automatically. That confirmation is a human's, except for the one case no
     * text can make ambiguous (a single statutory member with a SOLE rule), which the attestation
     * service confirms itself. Everything else reviews.
     */
    fun registryVerified(
        extract: RegistryExtract,
        decision: RepresentationDecision,
        at: Instant,
    ): BusinessOnboardingCase {
        require(status == CaseStatus.IDENTIFIER_ENTERED || status == CaseStatus.MANUAL_REVIEW) {
            "registry verification is not applicable in status $status"
        }
        return when {
            extract.status != EntityStatus.ACTIVE -> review(extract, "entity is ${extract.status} in the register", at)
            extract.verification == ExtractVerification.UNVERIFIED -> review(
                extract,
                "extract awaits operator attestation",
                at,
            )
            else -> applyRepresentation(extract, decision, at)
        }
    }

    private fun applyRepresentation(
        extract: RegistryExtract,
        decision: RepresentationDecision,
        at: Instant,
    ): BusinessOnboardingCase = when (decision) {
        is RepresentationDecision.Attested -> {
            val a = decision.attestation
            if (a.confirmedRoles.isNotEmpty()) {
                // The operator confirmed the rule names WHICH offices sign. Who signs is checked
                // against those offices when co-signers are invited; the count alone is not enough.
                copy(
                    status = CaseStatus.REGISTRY_VERIFIED,
                    extract = extract,
                    requiredSignatures = a.confirmedSigners,
                    requiredSignerRoles = a.confirmedRoles,
                    updatedAt = at,
                )
            } else {
                copy(
                    status = CaseStatus.REGISTRY_VERIFIED,
                    extract = extract,
                    requiredSignatures = a.confirmedSigners,
                    requiredSignerRoles = emptyList(),
                    updatedAt = at,
                )
            }
        }

        // The rule text CHANGED since the last confirmation. Say so rather than present a blank
        // form: this is the event the whole control exists for, and a reviewer who is not told
        // will re-confirm from memory of a company they have seen before.
        is RepresentationDecision.Superseded -> review(
            extract,
            "representation rule CHANGED since it was confirmed by ${decision.previous.attestedBy} " +
                "on ${decision.previous.attestedAt} (then: ${decision.previous.confirmedSigners} signature(s)" +
                "${roleSuffix(decision.previous.confirmedRoles)}). Register now says: " +
                "${decision.rule.sourceText}",
            at,
        )

        is RepresentationDecision.Unattested -> review(
            extract,
            "representation rule awaits confirmation — parser suggests ${suggestion(decision.rule)}: " +
                "${decision.rule.sourceText}",
            at,
        )
    }

    /**
     * Every named office must be filled by a DISTINCT chosen signer (#9711). Counting alone lets a
     * chair-plus-member rule be satisfied by two ordinary members, which is the exact mistake the
     * office list exists to stop; matching without distinctness lets ONE person who happens to be
     * both chair and member satisfy a two-office rule on their own signature.
     *
     * **Matching is at a WORD BOUNDARY, not by substring.** The obvious `role.contains(office)`
     * is wrong for the one pair the Czech register uses most: `mistopredseda predstavenstva`
     * CONTAINS `predseda`, so two vice-chairs — and boards routinely have more than one — would
     * satisfy a `predseda` + `mistopredseda` rule with no chair on the agreement. An office
     * therefore has to begin a word: it matches `předseda představenstva` and not
     * `místopředseda představenstva`, and an operator may still type a stem (`predsed`).
     *
     * **The assignment is a real bipartite matching, not greedy first-fit.** Greedy also fails on
     * ordering: with offices [predseda, mistopredseda] and signers listed vice-chair first, a
     * first-fit that let `predseda` take the vice-chair would then reject a genuine chair+vice
     * pair. The sets here are tiny (a statutory body, a handful of offices), so an augmenting-path
     * search costs nothing and cannot answer "no" to a coverable set.
     */
    private fun requireOfficesCovered(ex: RegistryExtract, chosen: List<Signer>, stage: String) {
        val unfilled = copy(extract = ex).officeShortfall(chosen)
        if (unfilled.isNotEmpty()) {
            throw CaseTransitionException(
                "the representation rule requires " +
                    "${requiredSignerRoles.filter { it.isNotBlank() }.joinToString(", ")}; " +
                    "$stage does not cover: ${unfilled.joinToString(", ")}",
            )
        }
    }

    private fun foldedRoles(ex: RegistryExtract, chosen: List<Signer>): List<String?> = chosen.map { s ->
        // getOrNull: a sole trader's initiatorMatched does not bounce an out-of-range index, and a
        // raw IndexOutOfBounds here would surface as an unmapped 500.
        s.representativeIndex
            ?.let { ex.representatives.getOrNull(it) }
            ?.let { CzechRepresentationRuleParser.fold(it.role.orEmpty()) }
    }

    /** One augmenting step: seat [officeIdx] on a free signer, displacing an earlier office if it can re-seat. */
    private fun assign(
        officeIdx: Int,
        office: String,
        offices: List<String>,
        roles: List<String?>,
        assignedTo: IntArray,
        visited: BooleanArray,
    ): Boolean {
        for (i in roles.indices) {
            if (visited[i] || !holdsOffice(roles[i], office)) continue
            visited[i] = true
            val incumbent = assignedTo[i]
            if (incumbent == -1 || assign(incumbent, offices[incumbent], offices, roles, assignedTo, visited)) {
                assignedTo[i] = officeIdx
                return true
            }
        }
        return false
    }

    /** [office] must BEGIN a word of the register's role wording; see [requireOfficesCovered]. */
    private fun holdsOffice(role: String?, office: String): Boolean {
        if (role == null) return false
        val folded = masculine(CzechRepresentationRuleParser.fold(office))
        if (folded.isEmpty()) return false
        return Regex("(^|\\W)" + Regex.escape(folded)).containsMatchIn(masculine(role))
    }

    /**
     * Rewrites the feminine spellings the register actually uses to the masculine stem, on BOTH
     * sides, so `předsedkyně představenstva` satisfies an office of `predseda`.
     *
     * An explicit list rather than suffix-stripping: trimming `-kyně`/`-ka` generically would make
     * unrelated words collide, and the register's vocabulary of statutory offices is small enough
     * to enumerate. Without this a company chaired by a woman parses as covering no office at all —
     * and since the offices are now re-checked at SIGNATURE, that surfaces only after the
     * signatures have been collected, which is the worst moment to discover it.
     */
    private fun masculine(folded: String): String =
        FEMININE_OFFICES.entries.fold(folded) { acc, (f, m) -> acc.replace(f, m) }

    private fun roleSuffix(roles: List<String>) = if (roles.isEmpty()) "" else ", offices: ${roles.joinToString(", ")}"

    private fun suggestion(rule: RepresentationRule) = when {
        rule.isRoleConstrained ->
            "${rule.requiredSigners} signature(s) from named offices " +
                "(${rule.requiredRoles.joinToString(", ")})"
        rule.mode == RepresentationMode.UNKNOWN -> "nothing — it could not parse the text"
        else -> "${rule.mode} / ${rule.requiredSigners ?: "all"} signature(s)"
    }

    private fun review(extract: RegistryExtract, reason: String, at: Instant) =
        copy(status = CaseStatus.MANUAL_REVIEW, extract = extract, reviewReason = reason, updatedAt = at)

    /** party-service has created the entity party. */
    fun entityPartyCreated(partyId: UUID, at: Instant): BusinessOnboardingCase =
        copy(entityPartyId = partyId, updatedAt = at)

    /**
     * The initiator says which listed representative they are, and party-service's VERIFIED record of
     * who they are decides whether that is true — the request's claimed name is never trusted for it.
     * Deliberately asymmetric outcomes:
     *
     *  - identity not verified, not a listed representative, or the name does not match → refused
     *    ([InitiatorIdentityMismatchException]). Opening an entity's account on someone else's behalf
     *    is not a review case; it is not allowed.
     *  - the name matches but the register's address for that person and the verified address
     *    disagree → MANUAL_REVIEW. People move and registers lag; a human looks, nobody is refused.
     *  - both agree → the signing mechanics below.
     */
    fun initiatorMatched(representativeIndex: Int?, identity: InitiatorIdentity, at: Instant): BusinessOnboardingCase {
        require(status == CaseStatus.REGISTRY_VERIFIED) { "initiator can only be matched after registry verification" }
        val ex = requireNotNull(extract)
        if (!identity.verified) {
            throw InitiatorIdentityMismatchException("complete identity verification before onboarding a company")
        }
        // A sole trader IS the single listed representative, whatever index the client sent.
        val rep = if (ex.isSoleTrader) {
            ex.representatives.firstOrNull()
        } else {
            representativeIndex?.let(
                ex.representatives::getOrNull,
            )
        }
        if (rep == null || !isVerifiedAs(ex, rep, identity)) {
            throw InitiatorIdentityMismatchException(
                "the verified identity does not match a listed representative of ${ex.legalName} — an account " +
                    "can only be opened by a person the register lists as able to act for the entity",
            )
        }
        val signer = initiatorSigner(representativeIndex, rep.fullName, rep.dateOfBirth, at)
        val next = copy(status = CaseStatus.INITIATOR_MATCHED, signers = listOf(signer), updatedAt = at)
        if (IdentityMatch.addressConflicts(rep.address, identity.address)) {
            return next.copy(
                status = CaseStatus.MANUAL_REVIEW,
                reviewReason = "the initiator matches ${rep.fullName} by name, but the register's address for that " +
                    "person differs from the verified address — confirm it is the same person",
                updatedAt = at,
            )
        }
        // A one-signature rule is ready immediately — unless it NAMES the office, in which case the
        // initiator has to actually hold it. Without this clause a single-office rule would skip
        // the check in `cosignersInvited` entirely, because it never invites anyone.
        if (requireNotNull(requiredSignatures) > 1) return next
        if (requiredSignerRoles.isEmpty()) return next.copy(status = CaseStatus.READY_TO_SIGN)
        return try {
            next.requireOfficesCovered(ex, next.signers, "the initiator")
            next.copy(status = CaseStatus.READY_TO_SIGN)
        } catch (e: CaseTransitionException) {
            next.copy(status = CaseStatus.MANUAL_REVIEW, reviewReason = e.message, updatedAt = at)
        }
    }

    private fun isVerifiedAs(ex: RegistryExtract, rep: Representative, identity: InitiatorIdentity): Boolean =
        if (ex.isSoleTrader) {
            IdentityMatch.soleTraderIs(rep.fullName, identity.legalName)
        } else {
            IdentityMatch.samePerson(rep.fullName, identity.legalName)
        }

    private fun initiatorSigner(index: Int?, name: String, dob: LocalDate?, at: Instant) = Signer(
        id = Ids.newId(),
        representativeIndex = index,
        fullName = name,
        dateOfBirth = dob,
        partyId = initiatorPartyId,
        status = SignerStatus.IDENTIFIED,
        invitationToken = null,
        invitedAt = null,
        identifiedAt = at,
        signedAt = null,
        signatureRef = null,
        isInitiator = true,
    )

    /**
     * The initiator picks which OTHER listed representatives must co-sign. Enough must be chosen
     * to reach [requiredSignatures]; more is allowed (a company may want every director on it).
     */
    fun cosignersInvited(representativeIndexes: List<Int>, tokens: List<String>, at: Instant): BusinessOnboardingCase {
        require(status == CaseStatus.INITIATOR_MATCHED || status == CaseStatus.AWAITING_COSIGNERS) {
            "co-signers can only be invited after the initiator is matched (status $status)"
        }
        val ex = requireNotNull(extract)
        val required = requireNotNull(requiredSignatures)
        val initiatorIndex = initiator?.representativeIndex
        val distinct = representativeIndexes.distinct().filter { it != initiatorIndex }
        require(distinct.all { it in ex.representatives.indices }) { "unknown representative index" }
        require(tokens.size == distinct.size) { "one invitation token per invitee" }
        val invited = distinct.mapIndexed { i, idx ->
            val rep = ex.representatives[idx]
            Signer(
                id = Ids.newId(),
                representativeIndex = idx,
                fullName = rep.fullName,
                dateOfBirth = rep.dateOfBirth,
                partyId = null,
                status = SignerStatus.INVITED,
                invitationToken = tokens[i],
                invitedAt = at,
                identifiedAt = null,
                signedAt = null,
                signatureRef = null,
                isInitiator = false,
            )
        }
        val existingNonInitiator = signers.filter { !it.isInitiator && it.representativeIndex !in distinct }
        val all = listOfNotNull(initiator) + existingNonInitiator + invited
        if (all.size < required) {
            throw CaseTransitionException("the representation rule needs $required signers; ${all.size} selected")
        }
        requireOfficesCovered(ex, all, "the selected signers")
        return copy(status = CaseStatus.AWAITING_COSIGNERS, signers = all, updatedAt = at)
    }

    /** An invited person has onboarded as themselves and holds a verified individual party. */
    fun signerIdentified(token: String, partyId: UUID, at: Instant): BusinessOnboardingCase {
        val signer = signers.firstOrNull { it.invitationToken == token && it.status == SignerStatus.INVITED }
            ?: throw CaseTransitionException("no open invitation for this token")
        if (signers.any {
                it.partyId == partyId
            }
        ) {
            throw CaseTransitionException("this person is already a signer on the case")
        }
        val updated = signers.map {
            if (it.id ==
                signer.id
            ) {
                it.copy(partyId = partyId, status = SignerStatus.IDENTIFIED, identifiedAt = at)
            } else {
                it
            }
        }
        return copy(signers = updated, updatedAt = at).recomputeReadiness(at)
    }

    /**
     * The checks that must hold before anyone asks document-service about the ceremony: signing is
     * open, [partyId] is an identified signer, the agreement exists, THIS signer accepted its
     * annexes, and [signatureRef] names the case's own ceremony — never an arbitrary string.
     */
    @Suppress("ThrowsCount") // one distinct, client-visible refusal per precondition
    fun requireSignable(partyId: UUID, signatureRef: String): Signer {
        require(status == CaseStatus.READY_TO_SIGN || status == CaseStatus.AWAITING_COSIGNERS) {
            "signing is not open in status $status"
        }
        val signer = signers.firstOrNull { it.partyId == partyId }
            ?: throw CaseTransitionException("party $partyId is not a signer on this case")
        if (signer.status !=
            SignerStatus.IDENTIFIED
        ) {
            throw CaseTransitionException("signer is ${signer.status}, expected IDENTIFIED")
        }
        val a = agreement ?: throw AgreementConflictException(
            AgreementConflictException.NOT_PREPARED,
            "the business agreement has not been prepared for this case",
        )
        if (!a.acceptedByParty(partyId)) {
            throw AgreementConflictException(
                AgreementConflictException.DISCLOSURES_NOT_ACCEPTED,
                "accept the agreement's disclosure documents before signing",
            )
        }
        if (signatureRef != a.ceremonyId.toString()) {
            throw AgreementConflictException(
                AgreementConflictException.SIGNATURE_REF_MISMATCH,
                "signatureRef must be this case's signature ceremony id",
            )
        }
        return signer
    }

    /**
     * One identified signer has completed the signature ceremony. After the LAST required signature
     * the AML risk flags decide: any flag sends the case to MANUAL_REVIEW naming every flag
     * (the signatures stay valid; activation waits for the reviewer), otherwise the existing path.
     */
    fun signed(
        partyId: UUID,
        signatureRef: String,
        at: Instant,
        highRiskCountries: Set<String> = emptySet(),
    ): BusinessOnboardingCase {
        val signer = requireSignable(partyId, signatureRef)
        val updated = signers.map {
            if (it.id ==
                signer.id
            ) {
                it.copy(status = SignerStatus.SIGNED, signedAt = at, signatureRef = signatureRef)
            } else {
                it
            }
        }
        val next = copy(signers = updated, updatedAt = at)
        if (next.signedCount < requireNotNull(requiredSignatures)) return next
        // The offices are re-checked over the people who actually SIGNED. Checking them only when
        // co-signers were invited is not enough: invitations may exceed the count, so a rule of
        // "chair plus one member" could be satisfied on paper by inviting chair, vice and member
        // and then completed by chair + member, with the agreement bound by the wrong pair.
        val shortfall = next.officeShortfall(next.signedSigners)
        if (shortfall.isNotEmpty()) {
            return next.copy(
                status = CaseStatus.MANUAL_REVIEW,
                reviewReason = "signatures collected do not cover the offices the rule names: " +
                    "${shortfall.joinToString(", ")}. Resolve by inviting someone who holds them, " +
                    "or by accepting the case with those offices removed.",
                updatedAt = at,
            )
        }
        val flags = next.riskFlags(highRiskCountries)
        if (flags.isNotEmpty()) {
            return next.copy(
                status = CaseStatus.MANUAL_REVIEW,
                reviewReason = "AML risk review before activation: ${flags.joinToString("; ")}",
                updatedAt = at,
            )
        }
        return if (entityPartyActive) next.copy(status = CaseStatus.ACTIVE) else next.copy(status = CaseStatus.SIGNED)
    }

    fun riskFlags(highRiskCountries: Set<String>): List<String> = AmlRiskFlags.of(
        questionnaire,
        declarations,
        identifier.country ?: extract?.registeredAddress?.countryCode,
        highRiskCountries,
    )

    private fun requireCollecting(what: String) {
        if (status != CaseStatus.INITIATOR_MATCHED &&
            status != CaseStatus.READY_TO_SIGN &&
            status != CaseStatus.AWAITING_COSIGNERS
        ) {
            throw CaseTransitionException("$what cannot be changed in status $status")
        }
        if (signedCount > 0) throw CaseTransitionException("$what cannot be changed after a signature was given")
    }

    /** The AML questionnaire (AML Act §9, FATCA/CRS). Re-answering replaces the previous answers. */
    fun questionnaireAnswered(q: Questionnaire, by: UUID, at: Instant): BusinessOnboardingCase {
        requireCollecting("the questionnaire")
        val valid = q.validated()
        return copy(questionnaire = valid.copy(answeredAt = at, answeredBy = by), updatedAt = at)
    }

    /**
     * The declarations. [uboNames] are the beneficial owners the register reports for the entity;
     * together with the listed representatives they are the people a PEP status must cover — from
     * the person's customer profile where [known] has one, declared otherwise.
     */
    fun declarationsMade(
        d: Declarations,
        uboNames: List<String>,
        known: List<KnownPerson>,
        by: UUID,
        at: Instant,
    ): BusinessOnboardingCase {
        requireCollecting("the declarations")
        val required = uboNames + extract?.representatives.orEmpty().map { it.fullName }
        val valid = d.validated(required, known)
        return copy(declarations = valid.copy(declaredAt = at, declaredBy = by), updatedAt = at)
    }

    /**
     * Whether the agreement may be rendered: the answers are given and every required signer is
     * identified — the ceremony is created over the signers' parties, so an unidentified co-signer
     * could never sign it.
     */
    fun requireAgreementPreparable() {
        if (questionnaire == null || declarations == null) {
            throw AgreementConflictException(
                AgreementConflictException.PREREQUISITES_MISSING,
                "answer the questionnaire and make the declarations before the agreement is prepared",
            )
        }
        if (status != CaseStatus.READY_TO_SIGN) {
            throw CaseTransitionException(
                "the agreement can be prepared once every required signer is identified (status $status)",
            )
        }
    }

    /**
     * document-service rendered (or returned the existing) agreement. The same document and
     * ceremony keep their acceptances; anything else starts acceptance over, and once someone has
     * signed, the ceremony can no longer change.
     */
    fun agreementPrepared(record: AgreementRecord, at: Instant): BusinessOnboardingCase {
        requireAgreementPreparable()
        val current = agreement
        if (current != null && current.sameDocumentAs(record)) {
            return copy(
                agreement = current.copy(templateCode = record.templateCode, lang = record.lang),
                updatedAt = at,
            )
        }
        if (signedCount > 0) {
            throw AgreementConflictException(
                AgreementConflictException.AGREEMENT_LOCKED,
                "the agreement has already been signed and cannot be replaced",
            )
        }
        return copy(agreement = record.copy(acceptances = emptyList()), updatedAt = at)
    }

    /**
     * [accepted] must be EXACTLY the disclosure set document-service currently lists for the
     * agreement ([current]) — code, version and hash. Anything else is a stale screen: 409.
     */
    fun disclosuresAccepted(
        accepted: List<AcceptedDisclosure>,
        current: List<AcceptedDisclosure>,
        by: UUID,
        at: Instant,
    ): BusinessOnboardingCase {
        val a = agreement ?: throw AgreementConflictException(
            AgreementConflictException.NOT_PREPARED,
            "the business agreement has not been prepared for this case",
        )
        if (accepted.size != accepted.toSet().size || accepted.toSet() != current.toSet()) {
            throw AgreementConflictException(
                AgreementConflictException.DISCLOSURES_STALE,
                "the accepted documents do not match the current disclosure set — reload and accept again",
            )
        }
        return copy(
            agreement = a.copy(
                acceptedDisclosures = current,
                acceptedAt = at,
                acceptedBy = by,
                acceptances = a.acceptances.filter { it.partyId != by } + DisclosureAcceptance(by, at),
            ),
            updatedAt = at,
        )
    }

    /** The initiator or an identified signer — the people who may answer and sign for the entity. */
    fun isParticipant(partyId: UUID): Boolean = initiatorPartyId == partyId || signers.any { it.partyId == partyId }

    /** The entity party passed the KYC + AML activation gate (ADR-0267); the relationship is live. */
    fun entityPartyActivated(at: Instant): BusinessOnboardingCase {
        require(!status.isTerminal) { "case is already $status" }
        val flagged = copy(entityPartyActive = true, updatedAt = at)
        return if (status == CaseStatus.SIGNED) flagged.copy(status = CaseStatus.ACTIVE) else flagged
    }

    fun rejected(reason: String, at: Instant): BusinessOnboardingCase {
        require(!status.isTerminal) { "case is already $status" }
        return copy(status = CaseStatus.REJECTED, reviewReason = reason, updatedAt = at)
    }

    fun abandoned(at: Instant): BusinessOnboardingCase {
        require(!status.isTerminal) { "case is already $status" }
        return copy(status = CaseStatus.ABANDONED, updatedAt = at)
    }

    /** An operator has confirmed a manually attested extract or accepted a power of attorney. */
    fun reviewResolved(
        requiredSignatures: Int,
        at: Instant,
        requiredSignerRoles: List<String> = emptyList(),
    ): BusinessOnboardingCase {
        require(status == CaseStatus.MANUAL_REVIEW) { "not under review" }
        require(requiredSignatures >= 1) { "at least one signature is required" }
        val next = copy(
            requiredSignatures = requiredSignatures,
            // Normalised on the way IN, not as a safety guard — `holdsOffice` folds and
            // `requireOfficesCovered` drops blanks at match time, so matching is already
            // insensitive to both. What this fixes is what gets STORED and rendered: without it
            // `CaseResponse.requiredSignerRoles` echoes an operator's "Předseda " back to the
            // console, and two spellings of one office look like two different constraints.
            requiredSignerRoles = requiredSignerRoles
                .map { CzechRepresentationRuleParser.fold(it) }
                .filter { it.isNotBlank() },
            reviewReason = null,
            updatedAt = at,
        )
        if (initiator == null) return next.copy(status = CaseStatus.REGISTRY_VERIFIED)
        // An already fully-signed case must be COMPLETABLE from here. `signed()` sends a case to
        // review when the collected signatures miss an office, and the obvious remedy — accept it,
        // clearing the office list — used to land in READY_TO_SIGN with every signer already
        // SIGNED: `signed()` refuses a SIGNED signer, so nothing could ever finish it. Two valid
        // signatures collected, entity never activated, and no timer watches READY_TO_SIGN.
        if (next.signedCount >= requiredSignatures && next.officeShortfall(next.signedSigners).isEmpty()) {
            return if (next.entityPartyActive) {
                next.copy(status = CaseStatus.ACTIVE)
            } else {
                next.copy(status = CaseStatus.SIGNED)
            }
        }
        return next.copy(status = CaseStatus.INITIATOR_MATCHED).recomputeReadiness(at)
    }

    private fun recomputeReadiness(at: Instant): BusinessOnboardingCase {
        val required = requiredSignatures ?: return this
        val verified = signers.filter { it.status == SignerStatus.IDENTIFIED || it.status == SignerStatus.SIGNED }
        val collecting = status == CaseStatus.AWAITING_COSIGNERS || status == CaseStatus.INITIATOR_MATCHED
        if (!collecting || verified.size < required) return this
        // The count alone is not enough to OPEN signing. `cosignersInvited` permits more invitees
        // than the rule needs on purpose, so the people who actually turn up are a subset of the
        // people who were checked — and a subset that reaches the count can miss an office.
        val shortfall = officeShortfall(verified)
        if (shortfall.isEmpty()) return copy(status = CaseStatus.READY_TO_SIGN, updatedAt = at)
        // Declining SILENTLY is what made this dangerous to get wrong: the case kept its old
        // status with no reason recorded, and AWAITING_COSIGNERS is covered by the invitation-TTL
        // timer — so an office shortfall ended as an ABANDONED case that never said why.
        return review(
            requireNotNull(extract),
            "everyone expected has verified, but the offices the rule names are not covered: " +
                shortfall.joinToString(", "),
            at,
        )
    }

    /**
     * The offices [chosen] cannot cover, for the paths that must not throw. Empty means covered.
     *
     * Returns the shortfall rather than a boolean because every caller needs to SAY what is
     * missing: a case that stops moving without naming the reason is the failure mode this whole
     * check exists to avoid, one step later.
     */
    private fun officeShortfall(chosen: List<Signer>): List<String> {
        val offices = requiredSignerRoles.filter { it.isNotBlank() }
        if (offices.isEmpty()) return emptyList()
        val ex = extract ?: return offices
        val roles = foldedRoles(ex, chosen)
        val assignedTo = IntArray(roles.size) { -1 }
        return offices.filterIndexed { i, office ->
            !assign(i, office, offices, roles, assignedTo, BooleanArray(roles.size))
        }
    }

    companion object {
        /**
         * The feminine spellings the register uses, mapped to the masculine stem — applied to both
         * the operator's office and the register's role, so `předsedkyně představenstva` satisfies
         * `predseda`. An explicit list, not suffix-stripping: trimming `-kyně`/`-ka` generically
         * would make unrelated words collide, and the vocabulary of statutory offices is small.
         */
        val FEMININE_OFFICES = mapOf(
            "mistopredsedkyne" to "mistopredseda",
            "predsedkyne" to "predseda",
            "jednatelka" to "jednatel",
            "prokuristka" to "prokurista",
            "reditelka" to "reditel",
            "clenka" to "clen",
            "spolecnice" to "spolecnik",
        )

        fun start(id: UUID, identifier: LegalEntityIdentifier, initiatorPartyId: UUID, at: Instant) =
            BusinessOnboardingCase(
                id = id,
                identifier = identifier,
                initiatorPartyId = initiatorPartyId,
                status = CaseStatus.IDENTIFIER_ENTERED,
                extract = null,
                entityPartyId = null,
                requiredSignatures = null,
                signers = emptyList(),
                reviewReason = null,
                createdAt = at,
                updatedAt = at,
            )
    }
}
