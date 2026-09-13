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
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant

/** Raised when the rule text moved between rendering the confirmation form and submitting it. */
class StaleAttestationException(message: String) : RuntimeException(message)

/**
 * Human confirmation of a representation rule, per entity (#9711).
 *
 * The parser proposes; this decides. Nothing here consults the parser's verdict to gate anything —
 * it is recorded alongside the human's answer purely so the two can be compared later, which is the
 * only honest way to learn whether the heuristic is improving.
 */
@ApplicationScoped
open class RepresentationAttestationService : RepresentationAttestationUseCase {

    @Inject lateinit var attestations: RepresentationAttestationRepository

    @Inject lateinit var lookup: RegistryLookupUseCase

    @Inject lateinit var clock: Clock

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
        return RepresentationDecision.Unattested(rule)
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

    override suspend fun history(scheme: IdentifierScheme, identifier: String): List<RepresentationAttestation> =
        attestations.listFor(LegalEntityIdentifier.of(scheme, identifier))
}
