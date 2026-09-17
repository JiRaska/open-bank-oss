// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class StatutoryOperationState { PENDING, EXECUTED, CANCELLED, EXPIRED }
enum class StatutoryOperationKind { ISSUE, ACCEPT }

/** Frozen authority requirement for one exact delegation offer; PENDING grants no capability. */
data class StatutoryDelegationOperation(
    val id: UUID,
    val principalPartyId: UUID,
    val initiatorPartyId: UUID,
    val requestKey: String,
    val requestHash: String,
    val payloadJson: String,
    val policyId: UUID,
    val policyRevision: Long,
    val sourceCaseId: UUID,
    val ruleHash: String,
    val ruleSnapshotJson: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val state: StatutoryOperationState = StatutoryOperationState.PENDING,
    val executedAt: Instant? = null,
    val grantId: UUID? = null,
    val operationKind: StatutoryOperationKind = StatutoryOperationKind.ISSUE,
    val targetGrantId: UUID? = null,
    val expectedLifecycleRevision: Long? = null,
) {
    init {
        require(requestKey.isNotBlank() && requestKey.length <= MAX_REQUEST_KEY_LENGTH) {
            "statutory request key is invalid"
        }
        require(requestHash.matches(SHA256_HEX) && ruleHash.matches(SHA256_HEX)) {
            "statutory operation hashes must be SHA-256 hex"
        }
        require(
            payloadJson.length in MIN_EVIDENCE_LENGTH..MAX_EVIDENCE_LENGTH &&
                ruleSnapshotJson.length in MIN_EVIDENCE_LENGTH..MAX_EVIDENCE_LENGTH,
        ) {
            "statutory operation evidence size is invalid"
        }
        require(sha256(payloadJson) == requestHash && sha256(ruleSnapshotJson) == ruleHash) {
            "statutory operation evidence does not match its fingerprint"
        }
        require(policyRevision > 0) { "statutory policy revision must be positive" }
        val targetMatchesKind = when (operationKind) {
            StatutoryOperationKind.ISSUE -> targetGrantId == null && expectedLifecycleRevision == null
            StatutoryOperationKind.ACCEPT ->
                targetGrantId != null &&
                    expectedLifecycleRevision != null &&
                    expectedLifecycleRevision >= 0
        }
        require(targetMatchesKind) { "statutory operation target does not match its kind" }
        require(
            expiresAt.isAfter(createdAt) &&
                !expiresAt.isAfter(createdAt.plus(MAX_LIFETIME_DAYS, ChronoUnit.DAYS)),
        ) {
            "statutory operation must expire within seven days"
        }
        val executionEvidenceMatchesState = when (state) {
            StatutoryOperationState.EXECUTED -> executedAt != null && grantId != null
            else -> executedAt == null && grantId == null
        }
        require(executionEvidenceMatchesState) {
            "executed statutory operation must identify its grant and execution time"
        }
        require(
            operationKind != StatutoryOperationKind.ACCEPT ||
                state != StatutoryOperationState.EXECUTED ||
                grantId == targetGrantId,
        ) { "executed statutory acceptance must link the target grant" }
    }

    private companion object {
        const val MAX_REQUEST_KEY_LENGTH = 200
        const val MIN_EVIDENCE_LENGTH = 2
        const val MAX_EVIDENCE_LENGTH = 65_536
        const val MAX_LIFETIME_DAYS = 7L
        val SHA256_HEX = Regex("[0-9a-f]{64}")

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
