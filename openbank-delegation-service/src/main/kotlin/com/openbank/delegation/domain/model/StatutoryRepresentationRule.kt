// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import java.util.UUID

/** The human roster and office constraints copied from one signed representation policy. */
data class StatutoryRepresentative(
    val partyId: UUID,
    val registryRepresentativeIndices: Set<Int>,
    val officeTags: Set<String>,
)

enum class StatutoryRuleMode { JOINT_N, JOINT_ALL }

/** A frozen legal signing requirement, distinct from an employee approval group. */
data class StatutoryRepresentationRule(
    val policyId: UUID,
    val principalPartyId: UUID,
    val revision: Long,
    val sourceCaseId: UUID,
    val attestationId: UUID,
    val ruleTextHash: String,
    val mode: StatutoryRuleMode,
    val requiredSignatures: Int,
    val requiredOffices: List<String>,
    val registryRepresentativeCount: Int,
    val eligibleRepresentatives: List<StatutoryRepresentative>,
) {
    init {
        require(revision > 0 && ruleTextHash.matches(Regex("[0-9a-f]{64}"))) { "invalid signed rule identity" }
        require(registryRepresentativeCount > 0 && eligibleRepresentatives.isNotEmpty()) { "empty register roster" }
        require(eligibleRepresentatives.map { it.partyId }.distinct().size == eligibleRepresentatives.size) {
            "duplicate representative"
        }
        val mappedRows = eligibleRepresentatives.flatMap { it.registryRepresentativeIndices }
        require(
            eligibleRepresentatives.all { representative ->
                representative.registryRepresentativeIndices.isNotEmpty() &&
                    representative.officeTags.isNotEmpty() &&
                    representative.officeTags.all { it.isNotBlank() }
            } &&
                mappedRows.size == mappedRows.distinct().size &&
                mappedRows.all { it in 0 until registryRepresentativeCount },
        ) { "invalid register mapping" }
        require(requiredSignatures in MIN_JOINT_SIGNATURES..eligibleRepresentatives.size) { "invalid statutory quorum" }
        require(requiredOffices.all { it.isNotBlank() } && requiredOffices.size <= requiredSignatures) {
            "invalid required offices"
        }
        require(
            mode != StatutoryRuleMode.JOINT_ALL ||
                (
                    requiredSignatures == eligibleRepresentatives.size &&
                        mappedRows.toSet() == (0 until registryRepresentativeCount).toSet()
                    ),
        ) { "joint-all must identify every registered representative" }
        require(officesCoveredBy(eligibleRepresentatives)) { "roster cannot satisfy office constraints" }
    }

    /** Every approving person is eligible; distinct humans cover quorum and office slots. */
    fun satisfiedBy(approvers: Set<UUID>): Boolean {
        if (approvers.size < requiredSignatures) return false
        val byParty = eligibleRepresentatives.associateBy { it.partyId }
        if (!approvers.all(byParty::containsKey)) return false
        return officesCoveredBy(approvers.map(byParty::getValue))
    }

    private fun officesCoveredBy(people: List<StatutoryRepresentative>): Boolean {
        val slots = requiredOffices.map { it.trim().lowercase() }
        val assigned = IntArray(people.size) { -1 }

        fun assign(slot: Int, visited: BooleanArray): Boolean {
            people.forEachIndexed { human, representative ->
                if (visited[human] || slots[slot] !in representative.officeTags.map { it.trim().lowercase() }) {
                    return@forEachIndexed
                }
                visited[human] = true
                if (assigned[human] == -1 || assign(assigned[human], visited)) {
                    assigned[human] = slot
                    return true
                }
            }
            return false
        }
        return slots.indices.all { assign(it, BooleanArray(people.size)) }
    }

    private companion object {
        const val MIN_JOINT_SIGNATURES = 2
    }
}
