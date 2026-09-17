// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.util.UUID

/** The register's rule after human confirmation, never a customer-managed employee approval group. */
enum class RepresentationPolicyMode { SOLE, JOINT_N, JOINT_ALL }

/** One bank-identified human, tied to exact rows in the verified register extract. */
data class EligibleRepresentative(
    val partyId: UUID,
    val registryRepresentativeIndices: Set<Int>,
    val officeTags: Set<String>,
) {
    init {
        require(registryRepresentativeIndices.isNotEmpty() && registryRepresentativeIndices.all { it >= 0 }) {
            "a representative must map to a verified register row"
        }
        require(officeTags.isNotEmpty() && officeTags.all { it.isNotBlank() }) {
            "at least one verified representative office is required"
        }
    }

    @get:JsonIgnore
    val normalizedOfficeTags: Set<String> get() = officeTags.map(String::normalizedOffice).toSet()
}

/**
 * Immutable evidence for a statutory signing rule. A count alone cannot express office constraints.
 * Legacy mandates do not acquire one by inference. The eligible roster may be a bank-identified
 * subset for SOLE or JOINT_N: unmapped people cannot sign a bank operation. JOINT_ALL instead
 * requires the whole verified register roster, or "all" would mean only all known to the bank.
 */
data class RepresentationPolicySnapshot(
    val id: UUID,
    val principalPartyId: UUID,
    val revision: Long,
    val sourceCaseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val registrySource: String,
    val registrySourceRef: String?,
    val registryRepresentativeCount: Int,
    val mode: RepresentationPolicyMode,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val eligibleRepresentatives: List<EligibleRepresentative>,
    val evidenceRef: String,
    val effectiveFrom: Instant,
) {
    init {
        require(revision > 0) { "representation rule revision must be positive" }
        require(ruleTextHash.matches(Regex("[0-9a-f]{64}"))) { "rule text hash must be SHA-256 hex" }
        require(registrySource.isNotBlank()) { "verified registry source is required" }
        require(registryRepresentativeCount > 0) { "verified register must list representatives" }
        require(evidenceRef.isNotBlank()) { "verified rule evidence is required" }
        require(eligibleRepresentatives.isNotEmpty()) { "a rule needs identified representatives" }
        require(eligibleRepresentatives.map { it.partyId }.distinct().size == eligibleRepresentatives.size) {
            "representatives must be distinct people"
        }
        val mappedRows = eligibleRepresentatives.flatMap { it.registryRepresentativeIndices }
        require(mappedRows.size == mappedRows.distinct().size) { "a register row cannot identify two different people" }
        require(mappedRows.all { it < registryRepresentativeCount }) {
            "mapped representative does not exist in the verified register"
        }
        require(
            mode != RepresentationPolicyMode.JOINT_ALL ||
                mappedRows.toSet() == (0 until registryRepresentativeCount).toSet(),
        ) {
            "joint-all requires every verified register representative to be identified"
        }
        require(requiredSignatures in 1..eligibleRepresentatives.size) { "quorum must fit the verified roster" }
        require(requiredOffices.all { it.isNotBlank() } && requiredOffices.size <= requiredSignatures) {
            "required offices must fit the quorum"
        }
        require(mode != RepresentationPolicyMode.SOLE || requiredSignatures == 1) {
            "sole representation requires exactly one signature"
        }
        require(mode != RepresentationPolicyMode.JOINT_N || requiredSignatures >= 2) {
            "joint representation requires at least two signatures"
        }
        require(mode != RepresentationPolicyMode.JOINT_ALL || requiredSignatures == eligibleRepresentatives.size) {
            "joint-all requires every verified representative"
        }
        require(officesCoveredBy(eligibleRepresentatives)) {
            "verified roster cannot satisfy the required offices"
        }
    }

    /** Distinct signed humans must meet both the exact quorum and every office constraint. */
    fun satisfiedBy(signerPartyIds: Set<UUID>): Boolean {
        if (signerPartyIds.size < requiredSignatures) return false
        val eligible = eligibleRepresentatives.associateBy { it.partyId }
        if (!signerPartyIds.all(eligible::containsKey)) return false
        return officesCoveredBy(signerPartyIds.map(eligible::getValue))
    }

    /** A human can fill at most one office slot, even when their registry role carries many tags. */
    private fun officesCoveredBy(representatives: List<EligibleRepresentative>): Boolean {
        val slots = requiredOffices.map(String::normalizedOffice)
        val assignedSlot = IntArray(representatives.size) { -1 }

        fun assign(slot: Int, visited: BooleanArray): Boolean {
            representatives.forEachIndexed { human, representative ->
                if (visited[human] || slots[slot] !in representative.normalizedOfficeTags) return@forEachIndexed
                visited[human] = true
                if (assignedSlot[human] == -1 || assign(assignedSlot[human], visited)) {
                    assignedSlot[human] = slot
                    return true
                }
            }
            return false
        }

        return slots.indices.all { assign(it, BooleanArray(representatives.size)) }
    }
}

private fun String.normalizedOffice(): String = trim().lowercase()
