// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.`in`.AttestRepresentationCommand
import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.`in`.RegistryLookupUseCase
import com.openbank.kyb.application.port.`in`.RepresentationAttestationUseCase
import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.Optional

/** Raised when the rule text moved between rendering the confirmation form and submitting it. */
class StaleAttestationException(message: String) : RuntimeException(message)

/**
 * Human confirmation of a representation rule, per entity (#9711).
 *
 * The parser proposes; a human decides — with ONE narrow exception, [autoConfirmsSingleMember].
 * Outside it the parser's verdict gates nothing: it is recorded alongside the human's answer purely
 * so the two can be compared later, which is the only honest way to learn whether the heuristic is
 * improving.
 *
 * ## The single-member exception
 *
 * The risk a human confirmation guards against is a rule that reads SOLE while really demanding a
 * second signature. When the register lists exactly ONE member of the statutory body, there is no
 * second person who could be that signature: whatever the text says, the one member is the only
 * possible signatory, and one signature is the only count that can bind. So a VERIFIED, ACTIVE
 * extract whose rule parses SOLE/1 without named offices, and which lists exactly one
 * representative, is confirmed by the system and persisted like any other attestation (actor
 * [SYSTEM_ACTOR]) — so the audit trail, the parser measurement and the next lookup all see it.
 * Never over a previous attestation: a changed text still goes to a human ([RepresentationDecision.Superseded]).
 * Kill switch: `openbank.kyb.representation.auto-confirm-single-member`.
 */
@ApplicationScoped
open class RepresentationAttestationService : RepresentationAttestationUseCase {

    @Inject lateinit var attestations: RepresentationAttestationRepository

    @Inject lateinit var lookup: RegistryLookupUseCase

    @Inject lateinit var clock: Clock

    /** Kill switch for the single-member exception; see the class KDoc. Defaults on. */
    @ConfigProperty(name = "openbank.kyb.representation.auto-confirm-single-member", defaultValue = "true")
    lateinit var autoConfirmSingleMember: Optional<Boolean>

    private val log = Logger.getLogger(RepresentationAttestationService::class.java)

    override suspend fun decisionFor(scheme: IdentifierScheme, identifier: String): RepresentationDecision? {
        val id = LegalEntityIdentifier.of(scheme, identifier)
        val extract = lookup.lookup(LookupCommand(scheme, identifier)) ?: return null
        return decide(extract)
    }

    /**
     * The one place the three outcomes are derived. Kept here rather than in the case so the review
     * form and the case transition can never disagree about what the store says.
     */
    open suspend fun decide(extract: RegistryExtract): RepresentationDecision {
        val rule = extract.representationRule
        val hash = RepresentationAttestation.hashOf(rule.sourceText)
        attestations.findActive(extract.identifier, hash)?.let { return RepresentationDecision.Attested(it) }
        // Attested before, but about a DIFFERENT text — the amendment case, which is exactly when a
        // stale signature count does harm. The reviewer is told, not shown a blank form.
        attestations.findLatestFor(extract.identifier)?.let {
            return RepresentationDecision.Superseded(it, rule)
        }
        if (autoConfirmsSingleMember(extract)) {
            return RepresentationDecision.Attested(attestations.attest(systemAttestation(extract, hash)))
        }
        return RepresentationDecision.Unattested(rule)
    }

    /** The unambiguous case, and only it: see the class KDoc. */
    private fun autoConfirmsSingleMember(extract: RegistryExtract): Boolean {
        val rule = extract.representationRule
        return autoConfirmSingleMember.orElse(true) &&
            extract.verification == ExtractVerification.VERIFIED &&
            extract.status == EntityStatus.ACTIVE &&
            rule.mode == RepresentationMode.SOLE &&
            rule.requiredSigners == 1 &&
            !rule.isRoleConstrained &&
            extract.representatives.size == 1
    }

    private fun systemAttestation(extract: RegistryExtract, hash: String): RepresentationAttestation {
        val rule = extract.representationRule
        val saved = RepresentationAttestation(
            id = Ids.newId(),
            identifier = extract.identifier,
            ruleTextHash = hash,
            ruleText = rule.sourceText,
            parsedMode = rule.mode,
            parsedSigners = rule.requiredSigners,
            confirmedSigners = 1,
            confirmedRoles = emptyList(),
            attestedBy = SYSTEM_ACTOR,
            attestedAt = Instant.now(clock),
            note = "Confirmed automatically: the register lists exactly one member of the statutory body " +
                "and the rule reads sole representation, so no second signatory can exist.",
        )
        log.infof(
            "representation auto-confirmed for %s %s: single statutory member, 1 signature",
            extract.identifier.scheme.name,
            extract.identifier.value,
        )
        return saved
    }

    override suspend fun attest(cmd: AttestRepresentationCommand): RepresentationAttestation {
        val id = LegalEntityIdentifier.of(cmd.scheme, cmd.identifier)
        val extract = lookup.lookup(LookupCommand(cmd.scheme, cmd.identifier))
            ?: throw StaleAttestationException("no register record for ${id.scheme.displayName} ${id.value}")
        val rule = extract.representationRule
        val current = RepresentationAttestation.hashOf(rule.sourceText)
        // The submitted hash is the text the operator actually READ. If the register has moved since
        // the form was rendered, confirming would attest a rule nobody looked at.
        if (current != cmd.ruleTextHash) {
            throw StaleAttestationException(
                "the register text changed since this form was opened; re-read it and confirm again",
            )
        }
        val now = Instant.now(clock)
        val attestation = RepresentationAttestation(
            id = Ids.newId(),
            identifier = id,
            ruleTextHash = current,
            ruleText = rule.sourceText,
            parsedMode = rule.mode,
            parsedSigners = rule.requiredSigners,
            confirmedSigners = cmd.confirmedSigners,
            // Folded, not merely trimmed: the signer side is compared after the parser's fold, so
            // an operator typing "Předseda" would otherwise store an office that can never match
            // "předseda představenstva" as the register spells it.
            confirmedRoles = cmd.confirmedRoles
                .map { CzechRepresentationRuleParser.fold(it) }
                .filter { it.isNotBlank() },
            attestedBy = cmd.operator,
            attestedAt = now,
            note = cmd.note,
        )
        val saved = attestations.attest(attestation)
        log.infof(
            "representation attested for %s %s by %s: %d signature(s)%s (parser said %s/%s)",
            id.scheme.name,
            id.value,
            cmd.operator,
            saved.confirmedSigners,
            if (saved.confirmedRoles.isEmpty()) "" else " from ${saved.confirmedRoles.joinToString(", ")}",
            saved.parsedMode,
            saved.parsedSigners ?: "-",
        )
        return saved
    }

    companion object {
        const val SYSTEM_ACTOR = "system:single-statutory-member"
    }

    override suspend fun history(scheme: IdentifierScheme, identifier: String): List<RepresentationAttestation> =
        attestations.listFor(LegalEntityIdentifier.of(scheme, identifier))
}
