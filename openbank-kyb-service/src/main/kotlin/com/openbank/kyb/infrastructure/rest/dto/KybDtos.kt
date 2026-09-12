// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.rest.dto

import com.openbank.kyb.application.port.`in`.DeclaredEntity
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RegistrySearchHit
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Signer
import com.openbank.kyb.domain.model.UboFinding
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DeclaredEntityRequest(
    val legalName: String?,
    val countryCode: String?,
    val legalFormClass: String? = null,
    val addressLine1: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
) {
    fun toCommand(): DeclaredEntity {
        val name =
            legalName?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("declared.legalName is required")
        val cc =
            countryCode?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("declared.countryCode is required")
        return DeclaredEntity(name, cc, legalFormClass, addressLine1, city, postalCode)
    }
}

data class LookupRequest(val scheme: String?, val identifier: String?, val declared: DeclaredEntityRequest? = null) {
    fun scheme(): IdentifierScheme {
        val s = scheme?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("scheme is required")
        return runCatching { IdentifierScheme.valueOf(s.uppercase()) }.getOrElse {
            throw IllegalArgumentException("unknown identifier scheme '$s'")
        }
    }

    fun identifier(): String =
        identifier?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("identifier is required")
}

data class StartCaseRequest(
    val scheme: String?,
    val identifier: String?,
    val initiatorPartyId: UUID?,
    val declared: DeclaredEntityRequest? = null,
)

data class MatchInitiatorRequest(val representativeIndex: Int?, val claimedName: String?, val dateOfBirth: LocalDate?)

data class InviteCosignersRequest(val representativeIndexes: List<Int>?)

data class ClaimInvitationRequest(val partyId: UUID?)

data class SignRequest(val signatureRef: String?)

data class ResolveReviewRequest(val requiredSignatures: Int?, val requiredSignerRoles: List<String>? = null)

/** An operator confirms how one entity is represented (#9711). */
data class AttestRepresentationRequest(
    val confirmedSigners: Int?,
    /**
     * The offices that must sign, when the register names them. Empty or absent means a plain count
     * — any listed representatives may sign.
     */
    val confirmedRoles: List<String>? = null,
    /**
     * Hash of the rule text the operator actually read, taken from the decision response. Rejected
     * when it no longer matches the register, so a rule that changed while the form was open cannot
     * be confirmed unseen.
     */
    val ruleTextHash: String?,
    val note: String? = null,
)

data class AttestationResponse(
    val id: UUID,
    val scheme: String,
    val identifier: String,
    val ruleTextHash: String,
    val ruleText: String?,
    val parsedMode: String,
    val parsedSigners: Int?,
    val confirmedSigners: Int,
    val confirmedRoles: List<String>,
    val attestedBy: String,
    val attestedAt: Instant,
    val supersededAt: Instant?,
    val note: String?,
) {
    companion object {
        fun from(a: RepresentationAttestation) = AttestationResponse(
            id = a.id,
            scheme = a.identifier.scheme.name,
            identifier = a.identifier.value,
            ruleTextHash = a.ruleTextHash,
            ruleText = a.ruleText,
            parsedMode = a.parsedMode.name,
            parsedSigners = a.parsedSigners,
            confirmedSigners = a.confirmedSigners,
            confirmedRoles = a.confirmedRoles,
            attestedBy = a.attestedBy,
            attestedAt = a.attestedAt,
            supersededAt = a.supersededAt,
            note = a.note,
        )
    }
}

/**
 * What the review form renders. [state] is `ATTESTED` | `UNATTESTED` | `SUPERSEDED`; a SUPERSEDED
 * decision carries [previous] so the operator is told the rule CHANGED rather than shown a blank
 * form for a company they may recognise.
 */
data class RepresentationDecisionResponse(
    val state: String,
    val ruleText: String?,
    val ruleTextHash: String,
    val parserSuggestsSigners: Int?,
    val parserSuggestsRoles: List<String>,
    val parserMode: String,
    val attestation: AttestationResponse?,
    val previous: AttestationResponse?,
) {
    companion object {
        fun from(d: RepresentationDecision): RepresentationDecisionResponse = when (d) {
            is RepresentationDecision.Attested -> RepresentationDecisionResponse(
                state = "ATTESTED",
                ruleText = d.attestation.ruleText,
                ruleTextHash = d.attestation.ruleTextHash,
                parserSuggestsSigners = d.attestation.parsedSigners,
                parserSuggestsRoles = emptyList(),
                parserMode = d.attestation.parsedMode.name,
                attestation = AttestationResponse.from(d.attestation),
                previous = null,
            )

            is RepresentationDecision.Unattested -> ruleOnly("UNATTESTED", d.rule, null)
            is RepresentationDecision.Superseded -> ruleOnly("SUPERSEDED", d.rule, d.previous)
        }

        private fun ruleOnly(state: String, rule: RepresentationRule, previous: RepresentationAttestation?) =
            RepresentationDecisionResponse(
                state = state,
                ruleText = rule.sourceText,
                ruleTextHash = RepresentationAttestation.hashOf(rule.sourceText),
                parserSuggestsSigners = rule.requiredSigners,
                parserSuggestsRoles = rule.requiredRoles,
                parserMode = rule.mode.name,
                attestation = null,
                previous = previous?.let { AttestationResponse.from(it) },
            )
    }
}

data class RejectRequest(val reason: String?)

/**
 * One row of a name search (issue #9707). Carries what tells two companies apart and nothing that
 * could pass for a verified extract — no representatives, no rule, no status — so a client cannot
 * bind a case from a search hit without going through `/lookup`.
 */
data class SearchHitResponse(
    val scheme: String,
    val identifier: String,
    val name: String,
    val legalFormCode: String?,
    val legalForm: String?,
    val registeredAddress: String?,
) {
    companion object {
        fun from(h: RegistrySearchHit, legalForm: String?) = SearchHitResponse(
            scheme = h.identifier.scheme.name,
            identifier = h.identifier.value,
            name = h.name,
            legalFormCode = h.legalFormCode,
            legalForm = legalForm,
            registeredAddress = h.registeredAddress,
        )
    }
}

/**
 * [totalMatches] can exceed `hits.size`; [tooManyMatches] means the register refused to answer at
 * all (ARES caps at 1 000 matches and returns an error, not a page) and the customer should add a
 * town or more of the name. Both are for the UI to say, not to hide.
 */
data class SearchResponse(val hits: List<SearchHitResponse>, val totalMatches: Int, val tooManyMatches: Boolean)

data class SchemeResponse(
    val scheme: String,
    val country: String?,
    val displayName: String,
    val checksum: Boolean,
    val example: String,
)

data class RepresentativeResponse(
    val index: Int,
    val fullName: String,
    val dateOfBirth: LocalDate?,
    val body: String?,
    val role: String?,
    val since: LocalDate?,
)

data class ExtractResponse(
    val scheme: String,
    val identifier: String,
    val country: String?,
    val legalName: String,
    val legalFormCode: String?,
    val legalFormClass: String,
    val status: String,
    val registeredAddress: Map<String, String?>?,
    val incorporatedOn: LocalDate?,
    val taxId: String?,
    val representatives: List<RepresentativeResponse>,
    val representationRule: Map<String, Any?>,
    val source: String,
    val sourceRef: String?,
    val verification: String,
    val fetchedAt: Instant,
) {
    companion object {
        fun from(e: RegistryExtract) = ExtractResponse(
            scheme = e.identifier.scheme.name,
            identifier = e.identifier.value,
            country = e.identifier.country ?: e.registeredAddress?.countryCode,
            legalName = e.legalName,
            legalFormCode = e.legalFormCode,
            legalFormClass = e.legalFormClass.name,
            status = e.status.name,
            registeredAddress = e.registeredAddress?.let {
                mapOf(
                    "line1" to it.line1,
                    "city" to it.city,
                    "postalCode" to it.postalCode,
                    "countryCode" to it.countryCode,
                )
            },
            incorporatedOn = e.incorporatedOn,
            taxId = e.taxId,
            representatives = e.representatives.mapIndexed { i, r ->
                RepresentativeResponse(i, r.fullName, r.dateOfBirth, r.body, r.role, r.since)
            },
            representationRule = mapOf(
                "mode" to e.representationRule.mode.name,
                "requiredSigners" to e.representationRule.signaturesRequired(e.representatives.size),
                "sourceText" to e.representationRule.sourceText,
                // The offices the rule names, when it names them (#9709). Null for `requiredSigners`
                // beside a non-empty list is not a gap: a role-constrained rule has no answer
                // expressible as a count, and a console that showed only the number would invite the
                // reader to check it and stop.
                "requiredRoles" to e.representationRule.requiredRoles,
            ),
            source = e.source,
            sourceRef = e.sourceRef,
            verification = e.verification.name,
            fetchedAt = e.fetchedAt,
        )
    }
}

/** [invitationToken] is only rendered to the INITIATOR (the one who shares it); every other reader gets null. */
data class SignerResponse(
    val id: UUID,
    val representativeIndex: Int?,
    val fullName: String,
    val partyId: UUID?,
    val status: String,
    val isInitiator: Boolean,
    val invitedAt: Instant?,
    val identifiedAt: Instant?,
    val signedAt: Instant?,
    val invitationToken: String?,
) {
    companion object {
        fun from(s: Signer, revealToken: Boolean) = SignerResponse(
            id = s.id,
            representativeIndex = s.representativeIndex,
            fullName = s.fullName,
            partyId = s.partyId,
            status = s.status.name,
            isInitiator = s.isInitiator,
            invitedAt = s.invitedAt,
            identifiedAt = s.identifiedAt,
            signedAt = s.signedAt,
            invitationToken = if (revealToken) s.invitationToken else null,
        )
    }
}

data class CaseResponse(
    val id: UUID,
    val status: String,
    val scheme: String,
    val identifier: String,
    val country: String?,
    val initiatorPartyId: UUID,
    val entityPartyId: UUID?,
    val requiredSignatures: Int?,
    /** The offices the attested rule names; empty means any listed representatives may sign (#9711). */
    val requiredSignerRoles: List<String>,
    val signedCount: Int,
    val extract: ExtractResponse?,
    val signers: List<SignerResponse>,
    val reviewReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(c: BusinessOnboardingCase, viewerPartyId: UUID?) = CaseResponse(
            id = c.id,
            status = c.status.name,
            scheme = c.identifier.scheme.name,
            identifier = c.identifier.value,
            country = c.identifier.country ?: c.extract?.registeredAddress?.countryCode,
            initiatorPartyId = c.initiatorPartyId,
            entityPartyId = c.entityPartyId,
            requiredSignatures = c.requiredSignatures,
            requiredSignerRoles = c.requiredSignerRoles,
            signedCount = c.signedCount,
            extract = c.extract?.let { ExtractResponse.from(it) },
            signers = c.signers.map { SignerResponse.from(it, revealToken = viewerPartyId == c.initiatorPartyId) },
            reviewReason = c.reviewReason,
            createdAt = c.createdAt,
            updatedAt = c.updatedAt,
        )
    }
}

/**
 * One beneficial owner as the register states them (ADR-0284 D5).
 *
 * `band` is a band and not a percentage on purpose — see [com.openbank.kyb.domain.model.OwnershipBand].
 * `natureOfControl` carries the register's own vocabulary verbatim: an analyst deciding whether a
 * control is ownership or influence needs the words that were filed, not our paraphrase of them.
 */
data class BeneficialOwnerResponse(
    val fullName: String,
    val dateOfBirth: LocalDate?,
    val nationality: String?,
    val countryOfResidence: String?,
    val band: String,
    val natureOfControl: List<String>,
    val notifiedOn: LocalDate?,
    val corporate: Boolean,
)

/**
 * What is known about an entity's beneficial owners right now.
 *
 * `source` is the field to read first: REGISTER means a register answered, SELF_DECLARATION means
 * this jurisdiction has no queryable register and the customer must declare, UNAVAILABLE means a
 * register exists and could not be reached. An empty `owners` list means something different under
 * each — which is why `registerStatements` (a filed "no PSC identified") is carried separately
 * rather than collapsed into the absence.
 */
data class UboResponse(
    val scheme: String,
    val identifier: String,
    val source: String,
    val owners: List<BeneficialOwnerResponse>,
    val registerStatements: List<String>,
    val threshold: Double,
    val registerName: String?,
    val sourceRef: String?,
    val requiresDeclaration: Boolean,
    val fetchedAt: Instant,
) {
    companion object {
        fun from(f: UboFinding) = UboResponse(
            scheme = f.identifier.scheme.name,
            identifier = f.identifier.value,
            source = f.source.name,
            owners = f.owners.map {
                BeneficialOwnerResponse(
                    fullName = it.fullName,
                    dateOfBirth = it.dateOfBirth,
                    nationality = it.nationality,
                    countryOfResidence = it.countryOfResidence,
                    band = it.band.name,
                    natureOfControl = it.natureOfControl,
                    notifiedOn = it.notifiedOn,
                    corporate = it.corporate,
                )
            },
            registerStatements = f.registerStatements,
            threshold = f.threshold,
            registerName = f.registerName,
            sourceRef = f.sourceRef,
            requiresDeclaration = f.requiresDeclaration,
            fetchedAt = f.fetchedAt,
        )
    }
}
