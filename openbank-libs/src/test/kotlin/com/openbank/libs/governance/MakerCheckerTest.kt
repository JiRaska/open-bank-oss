// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MakerCheckerTest {

    private fun proposal(maker: String = "alice") =
        Proposal(id = "p1", action = "resume-dispatch", proposedBy = maker, proposedAt = Instant.EPOCH)

    @Test
    fun `approve by a different checker advances to APPROVED then EXECUTED`() {
        val approved = proposal().approve(checker = "bob", at = Instant.EPOCH)
        assertEquals(ProposalState.APPROVED, approved.state)
        assertEquals("bob", approved.decidedBy)

        val executed = approved.markExecuted(Instant.EPOCH)
        assertEquals(ProposalState.EXECUTED, executed.state)
        assertTrue(executed.isTerminal)
    }

    @Test
    fun `approve by the proposer violates four-eyes`() {
        val ex = assertThrows(MakerCheckerViolation::class.java) {
            proposal(maker = "alice").approve(checker = "alice", at = Instant.EPOCH)
        }
        assertTrue(ex.message!!.contains("four-eyes"))
    }

    @Test
    fun `reject by the proposer violates four-eyes`() {
        assertThrows(MakerCheckerViolation::class.java) {
            proposal(maker = "alice").reject(checker = "alice", at = Instant.EPOCH)
        }
    }

    @Test
    fun `cannot execute before approval`() {
        assertThrows(MakerCheckerViolation::class.java) {
            proposal().markExecuted(Instant.EPOCH)
        }
    }

    @Test
    fun `cannot approve an already-approved proposal`() {
        val approved = proposal().approve(checker = "bob", at = Instant.EPOCH)
        assertThrows(MakerCheckerViolation::class.java) {
            approved.approve(checker = "carol", at = Instant.EPOCH)
        }
    }

    @Test
    fun `only the maker may withdraw`() {
        assertThrows(MakerCheckerViolation::class.java) {
            proposal(maker = "alice").withdraw(by = "bob", at = Instant.EPOCH)
        }
        val withdrawn = proposal(maker = "alice").withdraw(by = "alice", at = Instant.EPOCH)
        assertEquals(ProposalState.WITHDRAWN, withdrawn.state)
    }

    @Test
    fun `analytics alias points at the governance type`() {
        val viaAlias: com.openbank.libs.analytics.Proposal<String> = proposal()
        assertEquals(ProposalState.PROPOSED, viaAlias.state)
    }
}
