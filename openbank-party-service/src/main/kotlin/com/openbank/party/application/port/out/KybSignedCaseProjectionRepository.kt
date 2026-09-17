// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import com.openbank.party.domain.model.RepresentationPolicySnapshot
import java.time.Instant
import java.util.UUID

data class KybSignedMandateHolder(val signerId: UUID, val partyId: UUID, val registryRepresentativeIndex: Int?)

data class KybSignedCaseProjection(
    val caseId: UUID,
    val principalPartyId: UUID,
    val payloadHash: String,
    val occurredAt: Instant,
    val requiredSignatures: Int,
    val soleTrader: Boolean,
    val holders: List<KybSignedMandateHolder>,
    val policy: RepresentationPolicySnapshot?,
) {
    init {
        require(payloadHash.matches(Regex("[0-9a-f]{64}"))) { "KYB event hash must be SHA-256 hex" }
        require(requiredSignatures > 0 && holders.size >= requiredSignatures) { "incomplete signed KYB case" }
        require(holders.map { it.signerId }.distinct().size == holders.size) { "duplicate KYB signer" }
        require(holders.map { it.partyId }.distinct().size == holders.size) { "duplicate KYB signer party" }
        require(holders.all { it.registryRepresentativeIndex == null || it.registryRepresentativeIndex >= 0 }) {
            "invalid KYB register index"
        }
        val policyMatches = policy == null ||
            (
                policy.sourceCaseId == caseId &&
                    policy.principalPartyId == principalPartyId &&
                    policy.requiredSignatures == requiredSignatures
                )
        require(policyMatches) { "KYB policy does not belong to the signed case" }
    }
}

interface KybSignedCaseProjectionRepository {
    /** True only for the first atomic projection; exact replay is false, conflicting replay fails. */
    suspend fun project(case: KybSignedCaseProjection): Boolean
}
