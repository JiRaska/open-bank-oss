// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.UUID

enum class JointRepresentationMode { JOINT_N, JOINT_ALL }

/** One verified human and the indices of the attested offices they may occupy. */
data class StatutoryRepresentative(val partyId: UUID, val officeSlots: Set<Int>, val mandateEvidenceHash: String)

/** The bounded, evidence-linked rule KYB attested for a known statutory roster. */
data class StatutoryRepresentationEvidence(
    val caseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val attestedAt: Instant,
    val mode: JointRepresentationMode,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val representatives: List<StatutoryRepresentative>,
) {
    init {
        require(HASH.matches(ruleTextHash)) { "representation evidence requires a SHA-256 rule hash" }
        require(representatives.size in 2..MAX_REPRESENTATIVES) { "invalid statutory roster size" }
        require(representatives.map { it.partyId }.toSet().size == representatives.size) {
            "statutory representatives must be distinct humans"
        }
        val mandateHashes = representatives.map { it.mandateEvidenceHash }
        require(mandateHashes.toSet().size == representatives.size && mandateHashes.all(HASH::matches)) {
            "each statutory representative needs a distinct mandate evidence digest"
        }
        require(requiredSignatures in 2..representatives.size) { "invalid statutory signature threshold" }
        require(mode != JointRepresentationMode.JOINT_ALL || requiredSignatures == representatives.size) {
            "a joint-all rule requires the entire roster"
        }
        require(requiredOffices.size <= requiredSignatures && requiredOffices.none { it.isBlank() }) {
            "invalid statutory office requirements"
        }
        require(representatives.all { member -> member.officeSlots.all { it in requiredOffices.indices } }) {
            "a representative was assigned an unknown statutory office"
        }
        require(satisfiedBy(representatives.map { it.partyId }.toSet())) {
            "the verified roster cannot satisfy the attested offices"
        }
    }

    fun satisfiedBy(signedBy: Set<UUID>): Boolean {
        val signed = representatives.filter { it.partyId in signedBy }
        if (signed.size < requiredSignatures || signed.size != signedBy.size) return false
        val assigned = mutableMapOf<UUID, Int>()
        return requiredOffices.indices.all { slot -> assignOffice(slot, signed, assigned, mutableSetOf()) }
    }

    private fun assignOffice(
        slot: Int,
        signed: List<StatutoryRepresentative>,
        assigned: MutableMap<UUID, Int>,
        seen: MutableSet<UUID>,
    ): Boolean = signed.any { member ->
        val previousSlot = assigned[member.partyId]
        val canMove = slot in member.officeSlots &&
            seen.add(member.partyId) &&
            (previousSlot == null || assignOffice(previousSlot, signed, assigned, seen))
        if (canMove) assigned[member.partyId] = slot
        canMove
    }

    private companion object {
        const val MAX_REPRESENTATIVES = 64
        val HASH = Regex("^[0-9a-f]{64}$")
    }
}

data class StatutoryRepresentationRule(
    val principalPartyId: UUID,
    val evidence: StatutoryRepresentationEvidence,
    val mandatesByRepresentative: Map<UUID, UUID>,
    val effectiveFrom: Instant,
    val validTo: Instant?,
) {
    init {
        require(mandatesByRepresentative.keys == evidence.representatives.map { it.partyId }.toSet()) {
            "every statutory representative must have a verified mandate"
        }
        require(!effectiveFrom.isBefore(evidence.attestedAt)) { "a statutory rule cannot predate attestation" }
        require(validTo == null || validTo.isAfter(effectiveFrom)) { "statutory rule validity must be positive" }
    }

    fun isEffectiveAt(at: Instant): Boolean = !at.isBefore(effectiveFrom) && (validTo == null || at.isBefore(validTo))
}
