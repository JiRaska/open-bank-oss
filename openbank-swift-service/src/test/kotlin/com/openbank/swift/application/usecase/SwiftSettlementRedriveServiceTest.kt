// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.application.usecase

import com.openbank.swift.application.port.out.RedriveBackoff
import com.openbank.swift.application.port.out.RescheduleOutcome
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.application.port.out.SwiftSettlementRedrivePort
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SwiftSettlementRedriveServiceTest {
    private val now = Instant.parse("2026-10-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repo = mockk<SwiftRepository>()
    private val port = mockk<SwiftSettlementRedrivePort>(relaxed = true)
    private val swift = mockk<SwiftService>()
    private val backoff = RedriveBackoff(Duration.ofMinutes(2), Duration.ofHours(1), 10)
    private val service = SwiftSettlementRedriveService(
        repo,
        port,
        swift,
        clock,
        Duration.ofMinutes(2),
        Duration.ofMinutes(2),
        backoff,
    )
    private val id = UUID.randomUUID()
    private val message = mockk<SwiftMessage>()

    @Test
    fun `an unclaimed message is never settled`(): Unit = runBlocking {
        coEvery { port.claim(id, any(), now, now.minus(Duration.ofMinutes(2)), now.plusSeconds(120)) } returns false
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.NOT_CLAIMED)
        coVerify(exactly = 0) { swift.settleAccepted(any()) }
        coVerify(exactly = 0) { repo.findById(any()) }
    }

    @Test
    fun `a proven booking releases the claim it holds`(): Unit = runBlocking {
        claimed()
        coEvery { swift.settleAccepted(message) } returns
            SwiftService.SettleAttempt(completed(), false, bookingProven = true)
        coEvery { port.release(id, any()) } returns true
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.SETTLED)
        coVerify(exactly = 1) { port.release(id, any()) }
        coVerify(exactly = 0) { port.reschedule(any(), any(), any(), any()) }
    }

    @Test
    fun `an unproven booking is counted and exhaustion is reported`(): Unit = runBlocking {
        claimed()
        coEvery { swift.settleAccepted(message) } returns SwiftService.SettleAttempt(message, false)
        coEvery { message.status } returns SwiftStatus.SENT
        coEvery { port.reschedule(id, any(), now, backoff) } returns RescheduleOutcome(10, null, exhausted = true)
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.EXHAUSTED)
        coEvery { port.reschedule(id, any(), now, backoff) } returns RescheduleOutcome(3, now, exhausted = false)
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.RESCHEDULED)
    }

    @Test
    fun `a booking proven after the claim was lost is a late completion, never a dropped proof`(): Unit = runBlocking {
        claimed()
        coEvery { swift.settleAccepted(message) } returns
            SwiftService.SettleAttempt(completed(), false, bookingProven = true)
        coEvery { port.release(id, any()) } returns false
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.LATE_COMPLETION)
        coVerify(exactly = 0) { port.reschedule(any(), any(), any(), any()) }
        coVerify(exactly = 0) { port.exhaust(any(), any(), any()) }
    }

    @Test
    fun `proof is independent of the preserved operator business status`(): Unit = runBlocking {
        claimed()
        coEvery { message.status } returns SwiftStatus.ACKNOWLEDGED
        coEvery { swift.settleAccepted(message) } returns
            SwiftService.SettleAttempt(message, permanent = false, bookingProven = true)
        coEvery { port.release(id, any()) } returns false
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.LATE_COMPLETION)
        coVerify(exactly = 0) { port.reschedule(any(), any(), any(), any()) }
    }

    @Test
    fun `bad stored data exhausts at once instead of spending the backoff`(): Unit = runBlocking {
        claimed()
        coEvery { message.status } returns SwiftStatus.SENT
        coEvery { swift.settleAccepted(message) } returns SwiftService.SettleAttempt(message, permanent = true)
        coEvery { port.exhaust(id, any(), now) } returns RescheduleOutcome(1, null, exhausted = true)
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.EXHAUSTED)
        coVerify(exactly = 0) { port.reschedule(any(), any(), any(), any()) }
        coVerify(exactly = 0) { port.release(any(), any()) }
    }

    @Test
    fun `an unexpected failure still counts the attempt`(): Unit = runBlocking {
        claimed()
        coEvery { swift.settleAccepted(message) } throws IllegalStateException("boom")
        coEvery { port.reschedule(id, any(), now, backoff) } returns RescheduleOutcome(1, now, exhausted = false)
        assertThat(service.redrive(id)).isEqualTo(SwiftSettlementRedriveService.Result.RESCHEDULED)
    }

    @Test
    fun `cancellation is rethrown and never counted as an attempt`(): Unit = runBlocking {
        claimed()
        coEvery { swift.settleAccepted(message) } throws CancellationException("shutdown")
        assertThatThrownBy { runBlocking { service.redrive(id) } }.isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { port.reschedule(any(), any(), any(), any()) }
        coVerify(exactly = 0) { port.release(any(), any()) }
    }

    @Test
    fun `backoff doubles and is bounded`() {
        assertThat((1..8).map { backoff.delayAfter(it).toMinutes() }).containsExactly(2, 4, 8, 16, 32, 60, 60, 60)
        assertThat(backoff.delayAfter(Int.MAX_VALUE)).isEqualTo(Duration.ofHours(1))
        assertThatThrownBy { RedriveBackoff(Duration.ZERO, Duration.ofHours(1), 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RedriveBackoff(Duration.ofMinutes(2), Duration.ofHours(1), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun claimed() {
        coEvery { port.claim(id, any(), any(), any(), any()) } returns true
        coEvery { repo.findById(id) } returns message
    }

    private fun completed(): SwiftMessage = mockk<SwiftMessage>().also {
        coEvery { it.status } returns SwiftStatus.COMPLETED
    }
}
