// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RepresentationPolicySnapshotTest {
    private val chair = UUID.randomUUID()
    private val memberA = UUID.randomUUID()
    private val memberB = UUID.randomUUID()

    private fun policy(requiredOffices: List<String> = listOf("Chair", "Member")) = RepresentationPolicySnapshot(
        id = UUID.randomUUID(),
        principalPartyId = UUID.randomUUID(),
        revision = 1,
        sourceCaseId = UUID.randomUUID(),
        ruleTextHash = "a".repeat(64),
        mode = RepresentationPolicyMode.JOINT_N,
        requiredSignatures = 2,
        requiredOffices = requiredOffices,
        eligibleRepresentatives = listOf(
            EligibleRepresentative(chair, setOf(" Chair ", "Member")),
            EligibleRepresentative(memberA, setOf("Member")),
            EligibleRepresentative(memberB, setOf("Member")),
        ),
        evidenceRef = "verified-case",
        effectiveFrom = Instant.parse("2026-09-17T00:00:00Z"),
    )

    @Test
    fun `two ordinary members cannot replace the required chair`() {
        val rule = policy()
        assertThat(rule.satisfiedBy(setOf(memberA, memberB))).isFalse()
        assertThat(rule.satisfiedBy(setOf(chair, memberA))).isTrue()
        assertThat(rule.satisfiedBy(setOf(chair))).isFalse()
        assertThat(rule.satisfiedBy(setOf(chair, UUID.randomUUID()))).isFalse()
    }

    @Test
    fun `repeated office requirements count distinct signers`() {
        val rule = policy(listOf("Member", "Member"))
        assertThat(rule.satisfiedBy(setOf(chair))).isFalse()
        assertThat(rule.satisfiedBy(setOf(chair, memberA))).isTrue()
        assertThat(rule.satisfiedBy(setOf(memberA, memberB))).isTrue()
    }

    @Test
    fun `incomplete or contradictory verified rules cannot become snapshots`() {
        assertThatThrownBy { policy(listOf("Chair", "Secretary")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { policy().copy(ruleTextHash = "unknown") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            policy().copy(eligibleRepresentatives = listOf(EligibleRepresentative(chair, setOf("Chair", "Member"))))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { policy().copy(requiredOffices = listOf("Chair", "Chair")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            policy().copy(
                eligibleRepresentatives = listOf(
                    EligibleRepresentative(chair, setOf("Chair", "Member")),
                    EligibleRepresentative(memberA, setOf("Secretary")),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
