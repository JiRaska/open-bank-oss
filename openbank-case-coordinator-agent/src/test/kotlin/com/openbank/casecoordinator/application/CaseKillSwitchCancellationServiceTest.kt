// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.application.port.out.CancellableCase
import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import com.openbank.casecoordinator.application.port.out.KillSwitchCommand
import com.openbank.casecoordinator.application.port.out.TemporalCaseCancellationPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import java.time.Instant

class CaseKillSwitchCancellationServiceTest {
    private val state = mockk<CaseKillSwitchStatePort>(relaxed = true)
    private val temporal = mockk<TemporalCaseCancellationPort>(relaxed = true)
    private val service = CaseKillSwitchCancellationService(state, temporal)

    @Test
    fun `global halt cancels every running pilot case then records no-action evidence`() {
        every { state.cancellableCases() } returns listOf(CancellableCase("case-1"), CancellableCase("case-2"))
        val command = command("*")

        service.handle(command)

        verify { state.apply(command) }
        verifyOrder {
            temporal.cancelAndAwait("case-1")
            state.recordHalted("case-1", command)
            temporal.cancelAndAwait("case-2")
            state.recordHalted("case-2", command)
        }
    }

    @Test
    fun `unrelated agent halt never changes the case projection or Temporal`() {
        service.handle(command("compliance-officer"))

        verify(exactly = 0) { state.apply(any()) }
        verify(exactly = 0) { temporal.cancelAndAwait(any()) }
        verify(exactly = 0) { state.recordHalted(any(), any()) }
    }

    @Test
    fun `clear removes the local projection without resurrecting workflows`() {
        val command = command("rca-investigator", operation = "agent.killswitch.cleared")
        service.handle(command)

        verify { state.clear("rca-investigator", command.occurredAt) }
        verify(exactly = 0) { temporal.cancelAndAwait(any()) }
    }

    private fun command(scope: String, operation: String = "agent.killswitch.set") = KillSwitchCommand(
        eventId = "11111111-1111-1111-1111-111111111111",
        operation = operation,
        scope = scope,
        reason = "operator emergency halt",
        setBy = "admin-1",
        occurredAt = Instant.parse("2026-09-22T10:00:00Z"),
    )
}
