// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PaymentApprovalRequirementTest {
    private val owner = UUID.randomUUID()
    private val maker = UUID.randomUUID()
    private val signerA = UUID.randomUUID()
    private val signerB = UUID.randomUUID()
    private val employeeA = UUID.randomUUID()
    private val employeeB = UUID.randomUUID()

    @Test
    fun `statutory and employee quorums are conjunctive rather than one flattened count`() {
        val requirement = requirement(
            PaymentApprovalClause(
                PaymentApprovalClauseKind.STATUTORY_MANDATES,
                setOf(signerA, signerB),
                requiredApprovals = 2,
                sourceReferences = setOf(UUID.randomUUID(), UUID.randomUUID()),
            ),
            PaymentApprovalClause(
                PaymentApprovalClauseKind.EMPLOYEE_GROUP,
                setOf(employeeA, employeeB),
                requiredApprovals = 2,
                sourceReferences = setOf(UUID.randomUUID()),
                sourceRevision = 3,
            ),
        )

        assertThat(requirement.hasQuorumFromVerifiedParties(setOf(signerA, signerB, employeeA))).isFalse()
        assertThat(requirement.hasQuorumFromVerifiedParties(setOf(signerA, employeeA, employeeB))).isFalse()
        assertThat(requirement.hasQuorumFromVerifiedParties(setOf(signerA, signerB, employeeA, employeeB))).isTrue()
    }

    @Test
    fun `a maker never counts and cannot leave an impossible quorum`() {
        val requirement = requirement(
            PaymentApprovalClause(
                PaymentApprovalClauseKind.STATUTORY_MANDATES,
                setOf(maker, signerA),
                requiredApprovals = 1,
                sourceReferences = setOf(UUID.randomUUID()),
            ),
        )
        assertThat(requirement.hasQuorumFromVerifiedParties(setOf(maker))).isFalse()
        assertThat(requirement.hasQuorumFromVerifiedParties(setOf(maker, signerA))).isTrue()

        assertThatThrownBy {
            requirement(
                PaymentApprovalClause(
                    PaymentApprovalClauseKind.STATUTORY_MANDATES,
                    setOf(maker, signerA),
                    requiredApprovals = 2,
                    sourceReferences = setOf(UUID.randomUUID()),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("impossible quorum")
    }

    @Test
    fun `a personal proposal requires the owner and refuses a forged group revision`() {
        val personal = requirement(PaymentApprovalClause(PaymentApprovalClauseKind.PERSONAL_OWNER, setOf(owner), 1))
        assertThat(personal.hasQuorumFromVerifiedParties(setOf(owner))).isTrue()
        assertThat(personal.hasQuorumFromVerifiedParties(setOf(signerA))).isFalse()

        assertThatThrownBy {
            PaymentApprovalClause(
                PaymentApprovalClauseKind.EMPLOYEE_GROUP,
                setOf(employeeA),
                requiredApprovals = 1,
                sourceReferences = setOf(UUID.randomUUID()),
                sourceRevision = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("revision")
    }

    private fun requirement(vararg clauses: PaymentApprovalClause) = PaymentApprovalRequirement(
        proposalId = UUID.randomUUID(),
        ownerPartyId = owner,
        makerPartyId = maker,
        instructionFingerprint = "a".repeat(64),
        clauses = clauses.toList(),
        capturedAt = Instant.parse("2026-09-18T10:00:00Z"),
    )
}
