// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.domain.model

import java.time.Instant
import java.util.UUID

/** Each independent source of authority must reach its own quorum; counts are never flattened. */
enum class PaymentApprovalClauseKind { PERSONAL_OWNER, STATUTORY_MANDATES, EMPLOYEE_GROUP }

/** A frozen roster and its evidence references, never a live grant or a decision. */
data class PaymentApprovalClause(
    val kind: PaymentApprovalClauseKind,
    val eligiblePartyIds: Set<UUID>,
    val requiredApprovals: Int,
    val sourceReferences: Set<UUID> = emptySet(),
    val sourceRevision: Long? = null,
) {
    init {
        require(eligiblePartyIds.isNotEmpty()) { "approval clause needs eligible humans" }
        require(requiredApprovals in 1..eligiblePartyIds.size) { "approval quorum exceeds eligible humans" }
        when (kind) {
            PaymentApprovalClauseKind.PERSONAL_OWNER ->
                require(sourceReferences.isEmpty() && sourceRevision == null) {
                    "personal-owner clause cannot cite entity authority"
                }
            PaymentApprovalClauseKind.STATUTORY_MANDATES ->
                require(sourceReferences.isNotEmpty() && sourceRevision == null) {
                    "statutory clause needs mandate references and no group revision"
                }
            PaymentApprovalClauseKind.EMPLOYEE_GROUP ->
                require(sourceReferences.size == 1 && sourceRevision != null && sourceRevision > 0) {
                    "employee clause needs one group reference and its positive revision"
                }
        }
    }
}

/** Immutable v1 evidence to be persisted with a proposed operation before decisions are accepted. */
data class PaymentApprovalRequirement(
    val proposalId: UUID,
    val ownerPartyId: UUID,
    val makerPartyId: UUID,
    val instructionFingerprint: String,
    val clauses: List<PaymentApprovalClause>,
    val capturedAt: Instant,
) {
    val schemaVersion: Int = SCHEMA_VERSION

    init {
        require(ownerPartyId != makerPartyId) { "proposal maker cannot be the owner" }
        require(SHA256_HEX.matches(instructionFingerprint)) { "instruction fingerprint must be SHA-256 hex" }
        require(clauses.map { it.kind }.distinct().size == clauses.size) { "duplicate approval clause" }
        val base = clauses.filter { it.kind != PaymentApprovalClauseKind.EMPLOYEE_GROUP }
        require(base.size == 1) { "exactly one owner or statutory clause is required" }
        if (base.single().kind == PaymentApprovalClauseKind.PERSONAL_OWNER) {
            require(base.single().eligiblePartyIds == setOf(ownerPartyId) && base.single().requiredApprovals == 1) {
                "personal-owner clause must require the account owner's own decision"
            }
        }
        require(
            clauses.all { clause ->
                (clause.eligiblePartyIds - makerPartyId).size >= clause.requiredApprovals
            },
        ) { "proposal maker exclusion leaves an impossible quorum" }
    }

    /** Caller must pass only parties with independently verified, instruction-bound SCA decisions. */
    fun hasQuorumFromVerifiedParties(verifiedPartyIds: Set<UUID>): Boolean {
        val distinctNonMakerDecisions = verifiedPartyIds - makerPartyId
        return clauses.all { clause ->
            (distinctNonMakerDecisions intersect clause.eligiblePartyIds).size >= clause.requiredApprovals
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
