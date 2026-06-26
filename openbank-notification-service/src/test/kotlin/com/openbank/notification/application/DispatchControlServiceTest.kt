// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.notification.application

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.notification.application.port.out.DispatchControlStore
import com.openbank.notification.domain.ops.DispatchControlSnapshot
import com.openbank.notification.domain.ops.DispatchState
import com.openbank.notification.domain.ops.ResumeAction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DispatchControlServiceTest {

    private class FakeStore : DispatchControlStore {
        val log = mutableListOf<DispatchControlSnapshot>()
        val proposals = mutableMapOf<String, Proposal<ResumeAction>>()

        override suspend fun current(controlKey: String): DispatchControlSnapshot? =
            log.filter { it.controlKey == controlKey }.maxByOrNull { it.version }

        override suspend fun append(snapshot: DispatchControlSnapshot) {
            log.add(snapshot)
        }

        override suspend fun history(controlKey: String, limit: Int): List<DispatchControlSnapshot> =
            log.filter { it.controlKey == controlKey }.sortedByDescending { it.version }.take(limit)

        override suspend fun saveProposal(proposal: Proposal<ResumeAction>) {
            proposals[proposal.id] = proposal
        }

        override suspend fun findProposal(id: String): Proposal<ResumeAction>? = proposals[id]
    }

    private class CapturingAudit : AuditEventPublisher {
        val events = mutableListOf<AuditEvent>()
        override suspend fun publish(event: AuditEvent) {
            events.add(event)
        }
    }

    private fun service(store: DispatchControlStore = FakeStore(), audit: AuditEventPublisher = CapturingAudit()) =
        DispatchControlService(store, audit).also {
            it.clock = Clock.fixed(Instant.parse("2026-01-15T10:15:30Z"), ZoneOffset.UTC)
        }

    @Test
    fun `default snapshot is enabled when store empty`(): Unit = runBlocking {
        val svc = service()
        assertEquals(DispatchState.ENABLED, svc.snapshot().state)
        assertFalse(svc.isHalted())
    }

    @Test
    fun `halt appends a halted snapshot and emits an audit event`(): Unit = runBlocking {
        val store = FakeStore()
        val audit = CapturingAudit()
        val svc = service(store, audit)

        val snap = svc.halt("alice", "incident-123")

        assertEquals(DispatchState.HALTED, snap.state)
        assertEquals("alice", snap.actor)
        assertTrue(snap.deferredReviewRequired)
        assertEquals(1L, snap.version)
        assertTrue(svc.isHalted())
        assertEquals(1, audit.events.size)
        assertEquals("notification.dispatch.halted", audit.events.single().operation)
    }

    @Test
    fun `propose resume creates a PROPOSED proposal`(): Unit = runBlocking {
        val store = FakeStore()
        val svc = service(store)
        svc.halt("alice", "incident-123")

        val proposal = svc.proposeResume("alice", "incident resolved")

        assertEquals(ProposalState.PROPOSED, proposal.state)
        assertEquals("alice", proposal.proposedBy)
        assertEquals(proposal, store.findProposal(proposal.id))
    }

    @Test
    fun `approve by the same actor is a four-eyes violation`() {
        val svc = service()
        val proposal = runBlocking { svc.proposeResume("alice", "resume please") }

        assertThrows(MakerCheckerViolation::class.java) {
            runBlocking { svc.approveResume(proposal.id, "alice", "self-approve") }
        }
    }

    @Test
    fun `approve by a different actor executes the resume and re-enables dispatch`(): Unit = runBlocking {
        val store = FakeStore()
        val svc = service(store)
        svc.halt("alice", "incident-123")
        val proposal = svc.proposeResume("alice", "incident resolved")

        val snap = svc.approveResume(proposal.id, "bob", "verified")

        assertEquals(DispatchState.ENABLED, snap.state)
        assertEquals("bob", snap.actor)
        assertFalse(snap.deferredReviewRequired)
        assertFalse(svc.isHalted())
        assertEquals(ProposalState.EXECUTED, store.findProposal(proposal.id)?.state)
    }

    @Test
    fun `approve of an unknown proposal throws NoSuchElement`() {
        val svc = service()
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { svc.approveResume("missing", "bob", null) }
        }
    }

    @Test
    fun `reject moves the proposal to REJECTED without re-enabling`(): Unit = runBlocking {
        val store = FakeStore()
        val svc = service(store)
        svc.halt("alice", "incident-123")
        val proposal = svc.proposeResume("alice", "incident resolved")

        val rejected = svc.rejectResume(proposal.id, "bob", "needs more checks")

        assertEquals(ProposalState.REJECTED, rejected.state)
        assertTrue(svc.isHalted())
    }
}
