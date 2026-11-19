// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.audit.decision.InputDigest
import java.time.Instant
import java.util.UUID

enum class StatutoryDelegationAction { ISSUE, ACCEPT }

enum class StatutoryDelegationStatus { COLLECTING, COMMITTED, REJECTED, CANCELLED }

/** The exact effective Party and KYB evidence, including distinct offices and signers. */
data class StatutoryDelegationRule(
    val principalPartyId: UUID,
    val caseId: UUID,
    val attestationId: UUID,
    val evidenceDigest: InputDigest,
    val mandatesByRepresentative: Map<UUID, UUID>,
    val validTo: Instant?,
    val requiredSignatures: Int = mandatesByRepresentative.size,
    val requiredOffices: Int = 0,
    val officeSlotsByRepresentative: Map<UUID, Set<Int>> = emptyMap(),
) {
    init {
        require(mandatesByRepresentative.size in 2..MAX_REPRESENTATIVES) { "joint authority requires a bounded roster" }
        require(principalPartyId !in mandatesByRepresentative) { "a company cannot sign as a human" }
        require(mandatesByRepresentative.values.toSet().size == mandatesByRepresentative.size) {
            "joint authority requires distinct mandates"
        }
        require(requiredSignatures in 2..mandatesByRepresentative.size && requiredOffices in 0..requiredSignatures) {
            "invalid statutory quorum or office count"
        }
        require(officeSlotsByRepresentative.keys.all { it in mandatesByRepresentative }) {
            "statutory office evidence requires a verified representative"
        }
        require(officeSlotsByRepresentative.values.flatten().all { it in 0 until requiredOffices }) {
            "statutory office evidence names an unknown slot"
        }
        require(satisfiedBy(mandatesByRepresentative.keys)) { "the statutory roster cannot satisfy its rule" }
    }

    fun satisfiedBy(signedBy: Set<UUID>): Boolean {
        if (signedBy.size < requiredSignatures || !mandatesByRepresentative.keys.containsAll(signedBy)) return false
        val assigned = mutableMapOf<UUID, Int>()
        return (0 until requiredOffices).all { slot -> assignOffice(slot, signedBy, assigned, mutableSetOf()) }
    }

    private fun assignOffice(
        slot: Int,
        signedBy: Set<UUID>,
        assigned: MutableMap<UUID, Int>,
        seen: MutableSet<UUID>,
    ): Boolean = signedBy.any { member ->
        val previous = assigned[member]
        val canMove = slot in officeSlotsByRepresentative[member].orEmpty() &&
            seen.add(member) &&
            (previous == null || assignOffice(previous, signedBy, assigned, seen))
        if (canMove) assigned[member] = slot
        canMove
    }

    private companion object {
        const val MAX_REPRESENTATIVES = 64
    }
}

/** The authority, terms and target revision a representative sees before device approval. */
data class StatutoryDelegationOperation(
    val id: UUID,
    val action: StatutoryDelegationAction,
    val delegationId: UUID,
    val expectedLifecycleRevision: Long?,
    val initiatorPartyId: UUID,
    val rule: StatutoryDelegationRule,
    val frozenPayload: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val signatures: List<ApprovalSignature> = emptyList(),
    val status: StatutoryDelegationStatus = StatutoryDelegationStatus.COLLECTING,
    val closedBy: UUID? = null,
) {
    init {
        require((action == StatutoryDelegationAction.ISSUE) == (expectedLifecycleRevision == null)) {
            "only acceptance binds an existing delegation revision"
        }
        require(expectedLifecycleRevision == null || expectedLifecycleRevision >= 0) {
            "invalid delegation revision"
        }
        require(initiatorPartyId in rule.mandatesByRepresentative) { "initiator is not a statutory representative" }
        require(frozenPayload.isNotBlank()) { "statutory delegation requires frozen terms" }
        require(expiresAt.isAfter(createdAt) && (rule.validTo == null || !expiresAt.isAfter(rule.validTo))) {
            "the signature collection window must be within the statutory rule validity"
        }
        require(signatures.map { it.partyId }.toSet().size == signatures.size) { "duplicate statutory signer" }
        require(signatures.map { it.scaChallengeId }.toSet().size == signatures.size) { "duplicate SCA challenge" }
        require(signatures.all { it.partyId in rule.mandatesByRepresentative && isWithinWindow(it.at) }) {
            "statutory signature must belong to the verified roster and operation window"
        }
        require(status != StatutoryDelegationStatus.COMMITTED || isFullySigned) {
            "committed statutory operation requires the verified signature quorum"
        }
        val explicitlyClosed = status in setOf(StatutoryDelegationStatus.REJECTED, StatutoryDelegationStatus.CANCELLED)
        require((closedBy != null) == explicitlyClosed) { "statutory closure must record its actor" }
        require(closedBy == null || closedBy in rule.mandatesByRepresentative) {
            "statutory closure requires a verified roster member"
        }
    }

    val approvalRequestId: String
        get() = "delegation-statutory-${action.name.lowercase()}:$id"

    val payloadDigest: InputDigest
        get() = InputDigest.sha256(
            mapOf(
                "action" to action.name,
                "operationId" to id.toString(),
                "delegationId" to delegationId.toString(),
                "expectedRevision" to expectedLifecycleRevision?.toString().orEmpty(),
                "principalPartyId" to rule.principalPartyId.toString(),
                "attestationId" to rule.attestationId.toString(),
                "caseId" to rule.caseId.toString(),
                "ruleDigest" to rule.evidenceDigest.hex,
                "mandates" to CanonicalJson.write(rule.mandatesByRepresentative.mapKeys { it.key.toString() }),
                "payload" to frozenPayload,
            ),
        )

    val isFullySigned: Boolean
        get() = rule.satisfiedBy(signatures.map { it.partyId }.toSet())

    fun sign(
        actor: UUID,
        challenge: UUID,
        signedAt: Instant,
        current: StatutoryDelegationRule,
    ): StatutoryDelegationOperation {
        requireCollecting(actor, current, signedAt)
        val existing = signatures.singleOrNull { it.partyId == actor }
        if (existing?.scaChallengeId == challenge) return this
        require(existing == null && signatures.none { it.scaChallengeId == challenge }) {
            "statutory signatures and their SCA challenges must be distinct"
        }
        return copy(signatures = signatures + ApprovalSignature(actor, challenge, signedAt))
    }

    fun commit(current: StatutoryDelegationRule, at: Instant): StatutoryDelegationOperation {
        requireCollecting(initiatorPartyId, current, at)
        require(isFullySigned) { "statutory operation requires the verified signature quorum" }
        return copy(status = StatutoryDelegationStatus.COMMITTED)
    }

    fun close(
        actor: UUID,
        cancel: Boolean,
        current: StatutoryDelegationRule,
        at: Instant,
    ): StatutoryDelegationOperation {
        requireCollecting(actor, current, at)
        require(!cancel || actor == initiatorPartyId) { "only the initiator can cancel signature collection" }
        val state = if (cancel) StatutoryDelegationStatus.CANCELLED else StatutoryDelegationStatus.REJECTED
        return copy(status = state, closedBy = actor)
    }

    private fun requireCollecting(actor: UUID, current: StatutoryDelegationRule, at: Instant) {
        require(status == StatutoryDelegationStatus.COLLECTING) { "statutory operation is closed" }
        require(isWithinWindow(at)) { "statutory operation is outside its signing window" }
        require(rule == current) { "statutory rule is no longer current" }
        require(actor in rule.mandatesByRepresentative) { "actor is not a statutory representative" }
    }

    private fun isWithinWindow(at: Instant): Boolean = !at.isBefore(createdAt) && at.isBefore(expiresAt)
}
