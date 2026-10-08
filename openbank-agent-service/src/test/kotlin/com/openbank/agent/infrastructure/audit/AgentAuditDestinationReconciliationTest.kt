// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.agent.infrastructure.audit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AgentAuditDestinationReconciliationTest {
    private val first = evidence(1, 'a')
    private val second = evidence(2, 'b')
    private val other = evidence(3, 'c')

    private fun evidence(id: Long, digestCharacter: Char) =
        AgentAuditEvidenceIdentity(UUID(0, id), digestCharacter.toString().repeat(64))

    @Test
    fun `exact identities and payload digests reconcile regardless of export order`() {
        val result = AgentAuditDestinationReconciliation.compare(listOf(first, second), listOf(second, first))
        assertThat(result.count).isEqualTo(2)
        assertThat(result.identityManifestSha256).matches("[0-9a-f]{64}")
        assertThat(result).isEqualTo(
            AgentAuditDestinationReconciliation.compare(listOf(second, first), listOf(first, second)),
        )
    }

    @Test
    fun `missing destination identity fails closed`() {
        assertThatThrownBy { AgentAuditDestinationReconciliation.compare(listOf(first, second), listOf(first)) }
            .hasMessageContaining("identities or payload digests differ")
    }

    @Test
    fun `extra destination identity fails closed`() {
        assertThatThrownBy { AgentAuditDestinationReconciliation.compare(listOf(first), listOf(first, second)) }
            .hasMessageContaining("identities or payload digests differ")
    }

    @Test
    fun `different identities at equal count fail closed`() {
        assertThatThrownBy { AgentAuditDestinationReconciliation.compare(listOf(first, second), listOf(first, other)) }
            .hasMessageContaining("identities or payload digests differ")
    }

    @Test
    fun `duplicate source or destination identities fail even when hashes agree`() {
        assertThatThrownBy { AgentAuditDestinationReconciliation.compare(listOf(first, first), listOf(first)) }
            .hasMessageContaining("source manifest contains duplicate")
        assertThatThrownBy { AgentAuditDestinationReconciliation.compare(listOf(first), listOf(first, first)) }
            .hasMessageContaining("destination manifest contains duplicate")
        assertThatThrownBy {
            AgentAuditDestinationReconciliation.compare(listOf(first, first), listOf(first, first))
        }
            .hasMessageContaining("source manifest contains duplicate")
    }

    @Test
    fun `changed payload digest for unchanged event identity fails closed`() {
        assertThatThrownBy {
            AgentAuditDestinationReconciliation.compare(
                listOf(first),
                listOf(first.copy(payloadSha256 = "d".repeat(64))),
            )
        }.hasMessageContaining("identities or payload digests differ")
    }

    @Test
    fun `empty or malformed manifests fail closed`() {
        assertThatThrownBy { AgentAuditDestinationReconciliation.compare(emptyList(), emptyList()) }
            .hasMessageContaining("source manifest is empty")
        assertThatThrownBy {
            AgentAuditDestinationReconciliation.compare(
                listOf(first.copy(payloadSha256 = "A".repeat(64))),
                listOf(first),
            )
        }.hasMessageContaining("source manifest has an invalid digest")
        assertThatThrownBy {
            AgentAuditDestinationReconciliation.compare(
                listOf(first),
                listOf(first.copy(payloadSha256 = "bad")),
            )
        }.hasMessageContaining("destination manifest has an invalid digest")
    }
}
