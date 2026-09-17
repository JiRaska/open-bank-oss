// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import java.time.Instant
import java.util.UUID

/** The register's rule after human confirmation, never a customer-managed employee approval group. */
enum class RepresentationPolicyMode { SOLE, JOINT_N, JOINT_ALL }

/** One bank-identified human and their verified office in this exact rule revision. */
data class EligibleRepresentative(val partyId: UUID, val office: String) {
    init {
        require(office.isNotBlank()) { "representative office is required" }
    }

    val normalizedOffice: String get() = office.trim().lowercase()
}

/**
 * Immutable evidence for a statutory signing rule. A count alone cannot express office constraints.
 * Legacy mandates do not acquire one by inference; a separate KYB writer must supply the complete
 * bank-mapped roster before this snapshot can be used to govern a customer operation.
 */
data class RepresentationPolicySnapshot(
    val id: UUID,
    val principalPartyId: UUID,
    val revision: Long,
    val sourceCaseId: UUID,
    val ruleTextHash: String,
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
        require(evidenceRef.isNotBlank()) { "verified rule evidence is required" }
        require(eligibleRepresentatives.isNotEmpty()) { "a rule needs identified representatives" }
        require(eligibleRepresentatives.map { it.partyId }.distinct().size == eligibleRepresentatives.size) {
            "representatives must be distinct people"
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
        val available = eligibleRepresentatives.groupingBy { it.normalizedOffice }.eachCount()
        val required = requiredOffices.map(String::normalizedOffice).groupingBy { it }.eachCount()
        require(required.all { (office, count) -> available.getOrDefault(office, 0) >= count }) {
            "verified roster cannot satisfy the required offices"
        }
    }

    /** Distinct signed humans must meet both the exact quorum and every office constraint. */
    fun satisfiedBy(signerPartyIds: Set<UUID>): Boolean {
        if (signerPartyIds.size < requiredSignatures) return false
        val eligible = eligibleRepresentatives.associateBy { it.partyId }
        if (!signerPartyIds.all(eligible::containsKey)) return false
        val signedOffices = signerPartyIds.map { eligible.getValue(it).normalizedOffice }
            .groupingBy { it }.eachCount()
        val required = requiredOffices.map(String::normalizedOffice).groupingBy { it }.eachCount()
        return required.all { (office, count) -> signedOffices.getOrDefault(office, 0) >= count }
    }
}

private fun String.normalizedOffice(): String = trim().lowercase()
