// SPDX-License-Identifier: Apache-2.0

package com.openbank.domestic.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DomesticBookingClockTest {
    private val first = Instant.parse("2026-01-02T00:00:00Z")

    @Test
    fun `acceptance stamps once and completion preserves the due time`() {
        val clock = DomesticBookingClock().accepted(
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            first,
        )
        val retry = clock.accepted(
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.SENT_TO_CLEARING,
            first.plusSeconds(60),
        )
        assertThat(retry.firstDueAt).isEqualTo(first)
        val id = UUID.randomUUID()
        val done = retry.completed(
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
            id,
            first.plusSeconds(90),
        )
        assertThat(done.firstDueAt).isEqualTo(first)
        assertThat(done.provenAt).isEqualTo(first.plusSeconds(90))
        assertThat(done.transactionId).isEqualTo(id)
        assertThatThrownBy {
            done.completed(
                DomesticPaymentStatus.SENT_TO_CLEARING,
                DomesticPaymentStatus.SETTLED,
                UUID.randomUUID(),
                first,
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `legacy successful proof does not invent a historical booking start`() {
        val done = DomesticBookingClock().completed(
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.SETTLED,
            UUID.randomUUID(),
            first,
        )
        assertThat(done.firstDueAt).isNull()
        assertThat(done.provenAt).isEqualTo(first)
        assertThatThrownBy {
            DomesticBookingClock().accepted(DomesticPaymentStatus.RECEIVED, DomesticPaymentStatus.VALIDATED, first)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
