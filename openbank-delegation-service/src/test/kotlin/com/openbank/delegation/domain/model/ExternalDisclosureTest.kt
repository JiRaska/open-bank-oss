// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExternalDisclosureTest {
    private val now = OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.UTC)
    private val id = UUID.randomUUID()

    @Test
    fun `OTP verification and each view are bounded to this opaque disclosure`() {
        val disclosure = sample(maxViews = 1)

        val verified = disclosure.verifyOtpAttempt("248913", now)
        val viewed = verified.consumeView("opaque-link-secret", now.plusMinutes(1))

        assertThat(viewed.viewedAt).containsExactly(now.plusMinutes(1))
        assertThatThrownBy { viewed.consumeView("opaque-link-secret", now.plusMinutes(2)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unavailable")
    }

    @Test
    fun `revocation and missing OTP deny a view before document bytes are reached`() {
        val disclosure = sample().revoke(now)

        assertThatThrownBy { disclosure.verifyOtpAttempt("248913", now.plusSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unavailable")
        assertThatThrownBy { sample().consumeView("opaque-link-secret", now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("OTP")
    }

    @Test
    fun `five invalid OTP attempts lock the disclosure permanently`() {
        val locked = (1..ExternalDisclosure.MAX_FAILED_OTP_ATTEMPTS).fold(sample()) { disclosure, attempt ->
            disclosure.verifyOtpAttempt("00000$attempt", now.plusSeconds(attempt.toLong()))
        }

        assertThat(locked.failedOtpAttempts).isEqualTo(ExternalDisclosure.MAX_FAILED_OTP_ATTEMPTS)
        assertThat(locked.lockedAt).isNotNull
        assertThatThrownBy { locked.verifyOtpAttempt("248913", now.plusMinutes(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unavailable")
    }

    private fun sample(maxViews: Int = 2) = ExternalDisclosure(
        id = id,
        delegationId = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        recipientLabel = "External accountant",
        linkSecretHash = ExternalDisclosure.secretHash(id, "opaque-link-secret"),
        otpHash = ExternalDisclosure.secretHash(id, "248913"),
        expiresAt = now.plusDays(2),
        maxViews = maxViews,
        createdAt = now,
    )
}
