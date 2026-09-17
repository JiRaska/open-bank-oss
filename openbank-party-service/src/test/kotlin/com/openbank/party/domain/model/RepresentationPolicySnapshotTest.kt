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
        attestationId = UUID.randomUUID(),
        ruleTextHash = "a".repeat(64),
        registrySource = "verified-registry",
        registrySourceRef = "register-entry",
        registryRepresentativeCount = 3,
        mode = RepresentationPolicyMode.JOINT_N,
        requiredSignatures = 2,
        requiredOffices = requiredOffices,
        eligibleRepresentatives = listOf(
            EligibleRepresentative(chair, setOf(0), setOf(" Chair ", "Member")),
            EligibleRepresentative(memberA, setOf(1), setOf("Member")),
            EligibleRepresentative(memberB, setOf(2), setOf("Member")),
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
    fun `joint all requires the full register roster and all mapped humans`() {
        val rule = policy().copy(mode = RepresentationPolicyMode.JOINT_ALL, requiredSignatures = 3)
        assertThat(rule.satisfiedBy(setOf(chair, memberA))).isFalse()
        assertThat(rule.satisfiedBy(setOf(chair, memberA, memberB))).isTrue()
    }

    @Test
    fun `incomplete or contradictory verified rules cannot become snapshots`() {
        assertThatThrownBy { policy(listOf("Chair", "Secretary")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { policy().copy(ruleTextHash = "unknown") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            policy().copy(
                eligibleRepresentatives = listOf(
                    EligibleRepresentative(chair, setOf(0), setOf("Chair", "Member")),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { policy().copy(requiredOffices = listOf("Chair", "Chair")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val twoOfFour = policy().copy(registryRepresentativeCount = 4)
        assertThat(twoOfFour.satisfiedBy(setOf(chair, memberA))).isTrue()
        assertThat(twoOfFour.satisfiedBy(setOf(chair, UUID.randomUUID()))).isFalse()
        assertThatThrownBy {
            policy().copy(
                mode = RepresentationPolicyMode.JOINT_ALL,
                requiredSignatures = 3,
                registryRepresentativeCount = 4,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            policy().copy(
                eligibleRepresentatives = listOf(
                    EligibleRepresentative(chair, setOf(0), setOf("Chair", "Member")),
                    EligibleRepresentative(memberA, setOf(1), setOf("Secretary")),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            policy().copy(
                eligibleRepresentatives = listOf(
                    EligibleRepresentative(chair, setOf(0), setOf("Chair", "Member")),
                    EligibleRepresentative(memberA, setOf(0), setOf("Member")),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
