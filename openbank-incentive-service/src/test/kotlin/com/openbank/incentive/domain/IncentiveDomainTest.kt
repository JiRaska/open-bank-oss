// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class IncentiveDomainTest {
    private val now = Instant.parse("2026-08-26T12:00:00Z")
    private val ref = OfferRef(UUID.randomUUID(), "summer-current-account", 1)

    @Test
    fun `publication requires an independent checker and freezes a versioned reference`() {
        val draft = offer()
        assertThatThrownBy { draft.submit("checker") }.isInstanceOf(IllegalArgumentException::class.java)
        val submitted = draft.submit("maker")
        assertThatThrownBy { submitted.publish("maker") }.isInstanceOf(IllegalArgumentException::class.java)

        val published = submitted.publish("checker")
        assertThat(published.status).isEqualTo(OfferStatus.PUBLISHED)
        assertThat(published.ref).isEqualTo(ref)
        assertThat(published.accepts("current-account", now)).isTrue()
        assertThat(published.accepts("loan", now)).isFalse()
    }

    @Test
    fun `commit and release are idempotent but mutually exclusive`() {
        val reservation = reservation()
        val committed = reservation.commit(now.plusSeconds(1))
        assertThat(committed.commit(now.plusSeconds(2))).isSameAs(committed)
        assertThatThrownBy { committed.release() }.isInstanceOf(IllegalArgumentException::class.java)

        val released = reservation.release()
        assertThat(released.release()).isSameAs(released)
        assertThatThrownBy { released.commit(now.plusSeconds(2)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `expiry closes inventory and cannot be converted into success`() {
        val reservation = reservation()
        assertThatThrownBy { reservation.expire(now) }.isInstanceOf(IllegalArgumentException::class.java)
        val expired = reservation.expire(now.plus(16, ChronoUnit.MINUTES))
        assertThat(expired.status).isEqualTo(ReservationStatus.EXPIRED)
        assertThatThrownBy { expired.commit(now.plus(17, ChronoUnit.MINUTES)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `code representation accepts only a one-way digest`() {
        assertThat(CodeDigest("a".repeat(64)).value).hasSize(64)
        assertThatThrownBy { CodeDigest("SAVE20") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun offer() = IncentiveOffer(
        ref = ref,
        productScope = setOf("current-account"),
        effectiveFrom = now.minusSeconds(60),
        expiresAt = now.plus(30, ChronoUnit.DAYS),
        totalLimit = 1_000,
        perPartyLimit = 1,
        stackingPolicy = StackingPolicy.EXCLUSIVE,
        status = OfferStatus.DRAFT,
        maker = "maker",
    )

    private fun reservation() = PromoReservation(
        id = UUID.randomUUID(),
        offerRef = ref,
        codeDigest = CodeDigest("b".repeat(64)),
        partyRef = "party-1",
        productRef = "current-account",
        idempotencyKey = "checkout-1",
        reservedAt = now,
        expiresAt = now.plus(15, ChronoUnit.MINUTES),
    )
}
