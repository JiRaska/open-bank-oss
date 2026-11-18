// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.governance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PersonBoundProposalTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private val ttl = Duration.ofHours(24)

    // Two accounts of ONE person, and a second person.
    private val anaAccount1 = StaffIdentity("ana.1", "staff-9001", strongAuth = true)
    private val anaAccount2 = StaffIdentity("ana.2", "staff-9001", strongAuth = true)
    private val bob = StaffIdentity("bob", "staff-9002", strongAuth = true)

    private fun proposal() = Proposal.propose("p1", "release-hold", anaAccount1, t0, ttl)

    private fun violation(block: () -> Unit): String =
        assertThrows(MakerCheckerViolation::class.java) { block() }.message!!

    @Test
    fun `a different person with MFA approves and the decision records both account and person`() {
        val approved = proposal().approveBy(bob, t0.plusSeconds(60), "ok")
        assertEquals(ProposalState.APPROVED, approved.state)
        assertEquals("bob", approved.decidedBy)
        assertEquals("staff-9002", approved.decidedByPersonId)
        assertEquals("staff-9001", approved.proposerPersonId)
    }

    @Test
    fun `the same person through a second account is refused`() {
        val msg = violation { proposal().approveBy(anaAccount2, t0) }
        assertTrue(msg.contains("same person"), msg)
        violation { proposal().rejectBy(anaAccount2, t0) }
    }

    @Test
    fun `a decider without a person id is refused - fails closed`() {
        violation { proposal().approveBy(StaffIdentity("svc", null, strongAuth = true), t0) }
        violation { proposal().approveBy(StaffIdentity("svc", "  ", strongAuth = true), t0) }
    }

    @Test
    fun `a proposer without a person id cannot propose - fails closed`() {
        violation { Proposal.propose("p", "x", StaffIdentity("svc", null, strongAuth = true), t0, ttl) }
    }

    @Test
    fun `approval or rejection without a second factor is refused`() {
        val noMfa = bob.copy(strongAuth = false)
        assertTrue(violation { proposal().approveBy(noMfa, t0) }.contains("second factor"))
        violation { proposal().rejectBy(noMfa, t0) }
    }

    @Test
    fun `a conflicted person cannot decide`() {
        val msg = violation { proposal().approveBy(bob, t0, conflictedPersons = setOf("staff-9002")) }
        assertTrue(msg.contains("conflict of interest"), msg)
    }

    @Test
    fun `the proposing person withdraws from either account, nobody else can`() {
        violation { proposal().withdrawBy(bob, t0) }
        violation { proposal().withdrawBy(StaffIdentity("x", null, strongAuth = true), t0) }
        val withdrawn = proposal().withdrawBy(anaAccount2.copy(strongAuth = false), t0, "mistake")
        assertEquals(ProposalState.WITHDRAWN, withdrawn.state)
        assertEquals("ana.2", withdrawn.decidedBy)
        assertTrue(withdrawn.isSupersedable(t0))
        violation { withdrawn.approveBy(bob, t0) }
    }

    @Test
    fun `an expired proposal cannot be decided and becomes supersedable`() {
        val p = proposal()
        assertTrue(p.isOpen(t0.plus(ttl).minusMillis(1)))
        assertFalse(p.isSupersedable(t0))
        assertTrue(p.isExpired(t0.plus(ttl)))
        assertTrue(p.isSupersedable(t0.plus(ttl)))
        assertTrue(violation { p.approveBy(bob, t0.plus(ttl)) }.contains("expired"))
        violation { p.rejectBy(bob, t0.plus(ttl)) }
    }

    @Test
    fun `ttl must be positive and bounded`() {
        violation { Proposal.propose("p", "x", anaAccount1, t0, Duration.ZERO) }
        violation { Proposal.propose("p", "x", anaAccount1, t0, Proposal.MAX_TTL.plusSeconds(1)) }
        Proposal.propose("p", "x", anaAccount1, t0, Proposal.MAX_TTL)
    }

    @Test
    fun `the account-only verbs refuse a person-bound proposal`() {
        violation { proposal().approve("bob", t0) }
        violation { proposal().reject("bob", t0) }
        violation { proposal().withdraw("ana.1", t0) }
    }

    @Test
    fun `person verbs refuse a legacy account-only proposal`() {
        val legacy = Proposal(id = "l", action = "x", proposedBy = "alice", proposedAt = t0)
        violation { legacy.approveBy(bob, t0) }
        violation { legacy.withdrawBy(bob, t0) }
        assertEquals(ProposalState.APPROVED, legacy.approve("bob", t0).state)
    }
}
