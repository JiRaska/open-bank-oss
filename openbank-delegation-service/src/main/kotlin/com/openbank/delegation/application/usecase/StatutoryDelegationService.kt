// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationCommit
import com.openbank.delegation.application.port.out.StatutoryDelegationDecision
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.application.port.out.StatutoryDelegationRuleReader
import com.openbank.delegation.application.port.out.StatutoryDelegationSca
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryDelegationAction
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryDelegationRule
import com.openbank.delegation.domain.model.StatutoryDelegationStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The authenticated company and the human acting on its profile, supplied by the customer edge. */
data class StatutoryDelegationActor(val principalPartyId: UUID, val actorPartyId: UUID)

@ApplicationScoped
@Suppress("TooManyFunctions") // Both ceremonies share the same lock, current rule and device receipt checks.
class StatutoryDelegationService(
    private val delegation: DelegationService,
    private val grants: DelegationRepository,
    private val operations: StatutoryDelegationOperationRepository,
    private val rules: StatutoryDelegationRuleReader,
    private val sca: StatutoryDelegationSca,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.delegation.statutory-collection-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {

    suspend fun startIssue(
        actor: StatutoryDelegationActor,
        terms: StatutoryDelegationTerms,
        requestKey: String,
    ): StatutoryDelegationOperation {
        requireEnabled()
        require(actor.principalPartyId == terms.grantorPartyId) { "company profile does not match the grantor" }
        val fromExistingGrant = terms.issuedByOperationId != null || terms.validFrom != null
        val soleUsagePolicy = terms.approvalPolicy == ApprovalPolicy.SOLO && terms.approvalGroupRevision == null
        require(soleUsagePolicy && !fromExistingGrant) {
            "this statutory issue flow requires a grant with ordinary SOLO usage policy"
        }
        val normalized = terms.normalized()
        val existing = replay(actor, StatutoryDelegationAction.ISSUE, requestKey)
        if (existing != null) {
            if (existing.frozenPayload != mapper.writeValueAsString(normalized)) throw IdempotencyKeyReusedException()
            return existing
        }
        val operation = newOperation(actor, StatutoryDelegationAction.ISSUE, Ids.newId(), null, normalized)
        delegation.prepareStatutoryOffer(normalized, operation.delegationId, operation.id, utc(now()))
        return operations.create(requestKey, operation)
    }

    suspend fun startAccept(
        actor: StatutoryDelegationActor,
        delegationId: UUID,
        requestKey: String,
    ): StatutoryDelegationOperation {
        requireEnabled()
        val existing = replay(actor, StatutoryDelegationAction.ACCEPT, requestKey)
        if (existing != null) {
            if (existing.delegationId != delegationId) throw IdempotencyKeyReusedException()
            return existing
        }
        val grant = grants.findById(delegationId)
            ?.takeIf { it.granteePartyId == actor.principalPartyId } ?: throw NotFoundException()
        requireOffered(grant, grant.lifecycleRevision, now())
        val terms = StatutoryDelegationTerms.from(grant)
        delegation.verifyStatutoryAcceptance(grant, terms)
        val operation =
            newOperation(actor, StatutoryDelegationAction.ACCEPT, delegationId, grant.lifecycleRevision, terms)
        return operations.create(requestKey, operation)
    }

    suspend fun get(actor: StatutoryDelegationActor, id: UUID): StatutoryDelegationOperation {
        requireEnabled()
        val operation = operations.find(id)?.takeIf {
            it.rule.principalPartyId == actor.principalPartyId && actor.actorPartyId in it.rule.mandatesByRepresentative
        } ?: throw NotFoundException()
        if (rules.current(actor.principalPartyId) != operation.rule) throw NotFoundException()
        return operation
    }

    suspend fun pending(actor: StatutoryDelegationActor): List<StatutoryDelegationOperation> {
        requireEnabled()
        val rule = currentRule(actor)
        return operations.pending(actor.principalPartyId, actor.actorPartyId, MAX_PENDING).filter { it.rule == rule }
    }

    suspend fun sign(actor: StatutoryDelegationActor, id: UUID, challengeId: UUID): StatutoryDelegationOperation {
        val operation = get(actor, id)
        val human = actor.actorPartyId
        if (operation.hasSignature(human, challengeId)) return operation
        require(operation.status == StatutoryDelegationStatus.COLLECTING && now().isBefore(operation.expiresAt)) {
            "statutory operation is closed or expired"
        }
        require(operation.signatures.none { it.partyId == human }) { "a representative may sign only once" }

        val terms = mapper.readValue(operation.frozenPayload, StatutoryDelegationTerms::class.java)
        val spentAt = sca.consume(operation, human, challengeId)
        // The scoped receipt can be recovered if any authoritative recheck or the local commit fails.
        val prepared = prepareCommit(operation, terms)
        val currentRule = currentRule(actor)
        return operations.decideAtomically(id) { current, grant ->
            if (current.hasSignature(human, challengeId)) return@decideAtomically StatutoryDelegationDecision(current)
            val at = now()
            require(!spentAt.isAfter(at)) { "statutory receipt is ahead of the local decision clock" }
            val signed = current.sign(human, challengeId, spentAt, currentRule)
            if (signed.isFullySigned) {
                val committed = signed.commit(currentRule, at)
                StatutoryDelegationDecision(committed, commitGrant(committed, terms, grant, prepared, at))
            } else {
                require(at.isBefore(signed.expiresAt)) { "statutory operation is expired" }
                StatutoryDelegationDecision(signed)
            }
        } ?: throw NotFoundException()
    }

    suspend fun close(actor: StatutoryDelegationActor, id: UUID, cancel: Boolean): StatutoryDelegationOperation {
        val operation = get(actor, id)
        return operations.decideAtomically(id) { current, _ ->
            val desired = if (cancel) StatutoryDelegationStatus.CANCELLED else StatutoryDelegationStatus.REJECTED
            val next = if (current.status == desired && current.closedBy == actor.actorPartyId) {
                current
            } else {
                current.close(actor.actorPartyId, cancel, operation.rule, now())
            }
            StatutoryDelegationDecision(next)
        } ?: throw NotFoundException()
    }

    private suspend fun replay(
        actor: StatutoryDelegationActor,
        action: StatutoryDelegationAction,
        requestKey: String,
    ): StatutoryDelegationOperation? {
        val existing = operations.findByRequest(actor.principalPartyId, actor.actorPartyId, action, requestKey)
            ?: return null
        if (currentRule(actor) != existing.rule) {
            throw DelegationGrantorAuthorityException(
                "the statutory rule changed after this request",
            )
        }
        return existing
    }

    private suspend fun prepareCommit(
        operation: StatutoryDelegationOperation,
        terms: StatutoryDelegationTerms,
    ): DelegationGrant? = when (operation.action) {
        StatutoryDelegationAction.ISSUE ->
            delegation.prepareStatutoryOffer(terms, operation.delegationId, operation.id, utc(now()))

        StatutoryDelegationAction.ACCEPT -> {
            val grant = grants.findById(operation.delegationId) ?: throw NotFoundException()
            requireOffered(grant, operation.expectedLifecycleRevision, now())
            require(terms == StatutoryDelegationTerms.from(grant)) { "delegation terms have changed" }
            delegation.verifyStatutoryAcceptance(grant, terms)
            null
        }
    }

    private fun commitGrant(
        operation: StatutoryDelegationOperation,
        terms: StatutoryDelegationTerms,
        grant: DelegationGrant?,
        prepared: DelegationGrant?,
        at: Instant,
    ): StatutoryDelegationCommit = when (operation.action) {
        StatutoryDelegationAction.ISSUE -> {
            val issued = requireNotNull(prepared).copy(validFrom = utc(at), createdAt = utc(at), updatedAt = utc(at))
            StatutoryDelegationCommit(issued, issued.offeredEvent(at))
        }

        StatutoryDelegationAction.ACCEPT -> {
            val offered = requireNotNull(grant)
            requireOffered(offered, operation.expectedLifecycleRevision, at)
            require(terms == StatutoryDelegationTerms.from(offered)) { "delegation terms have changed" }
            val accepted = offered.acceptStatutorily(operation.id, utc(at))
            StatutoryDelegationCommit(accepted, accepted.activatedEvent(at))
        }
    }

    private suspend fun newOperation(
        actor: StatutoryDelegationActor,
        action: StatutoryDelegationAction,
        delegationId: UUID,
        expectedRevision: Long?,
        terms: StatutoryDelegationTerms,
    ): StatutoryDelegationOperation {
        val rule = currentRule(actor)
        val at = now()
        val expiresAt = listOfNotNull(at.plus(SIGNING_WINDOW), rule.validTo, terms.validTo?.toInstant()).min()
        return StatutoryDelegationOperation(
            id = Ids.newId(),
            action = action,
            delegationId = delegationId,
            expectedLifecycleRevision = expectedRevision,
            initiatorPartyId = actor.actorPartyId,
            rule = rule,
            frozenPayload = mapper.writeValueAsString(terms),
            createdAt = at,
            expiresAt = expiresAt,
        )
    }

    private suspend fun currentRule(actor: StatutoryDelegationActor): StatutoryDelegationRule =
        rules.current(actor.principalPartyId)?.takeIf { actor.actorPartyId in it.mandatesByRepresentative }
            ?: throw DelegationGrantorAuthorityException("no current verified statutory rule for this company profile")

    private fun requireOffered(grant: DelegationGrant, revision: Long?, at: Instant) {
        val withinValidity = grant.validTo?.toInstant()?.isAfter(at) ?: true
        require(grant.status == DelegationStatus.OFFERED && grant.lifecycleRevision == revision && withinValidity) {
            "delegation is no longer offered for these terms"
        }
    }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException()
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun utc(at: Instant): OffsetDateTime = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)

    private fun StatutoryDelegationOperation.hasSignature(actor: UUID, challengeId: UUID) =
        signatures.any { it.partyId == actor && it.scaChallengeId == challengeId }

    private companion object {
        val SIGNING_WINDOW: Duration = Duration.ofDays(7)
        const val MAX_PENDING = 50
    }
}
