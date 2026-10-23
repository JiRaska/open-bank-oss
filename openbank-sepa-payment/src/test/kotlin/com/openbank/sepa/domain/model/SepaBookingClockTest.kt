// SPDX-License-Identifier: Apache-2.0

package com.openbank.sepa.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SepaBookingClockTest {
    private val first = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `acceptance stamps once and completion preserves the due time`() {
        val clock = SepaBookingClock().accepted(
            SepaPaymentStatus.VALIDATED,
            SepaPaymentStatus.PROCESSING,
            first,
        )
        val retry = clock.accepted(
            SepaPaymentStatus.VALIDATED,
            SepaPaymentStatus.PROCESSING,
            first.plusSeconds(60),
        )
        assertThat(retry.firstDueAt).isEqualTo(first)
        val id = UUID.randomUUID()
        val done = retry.proven(
            id,
            first.plusSeconds(90),
        )
        assertThat(done.firstDueAt).isEqualTo(first)
        assertThat(done.provenAt).isEqualTo(first.plusSeconds(90))
        assertThat(done.transactionId).isEqualTo(id)
        assertThatThrownBy {
            done.proven(
                UUID.randomUUID(),
                first,
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `legacy successful proof does not invent a historical booking start`() {
        val done = SepaBookingClock().proven(
            UUID.randomUUID(),
            first,
        )
        assertThat(done.firstDueAt).isNull()
        assertThat(done.provenAt).isEqualTo(first)
        assertThatThrownBy {
            SepaBookingClock().accepted(SepaPaymentStatus.RECEIVED, SepaPaymentStatus.VALIDATED, first)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
