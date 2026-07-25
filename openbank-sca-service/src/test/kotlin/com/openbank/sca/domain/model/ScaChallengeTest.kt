// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ScaChallengeTest {

    private val now = OffsetDateTime.now()

    @Test
    fun `isExpired returns true when past expiresAt`() {
        val challenge = challenge(expiresAt = now.minusSeconds(5))

        assertThat(challenge.isExpired(now)).isTrue()
    }

    @Test
    fun `isExpired returns false when before expiresAt`() {
        val challenge = challenge(expiresAt = now.plusSeconds(60))

        assertThat(challenge.isExpired(now)).isFalse()
    }

    @Test
    fun `canAttempt returns true when pending and within limits`() {
        val challenge = challenge()

        assertThat(challenge.canAttempt(now)).isTrue()
    }

    @Test
    fun `canAttempt returns false when max attempts reached`() {
        val challenge = challenge(attemptCount = 3, maxAttempts = 3)

        assertThat(challenge.canAttempt(now)).isFalse()
    }

    @Test
    fun `complete sets status to COMPLETED`() {
        val challenge = challenge()

        val completed = challenge.complete(now)

        assertThat(completed.status).isEqualTo(ScaStatus.COMPLETED)
        assertThat(completed.completedAt).isNotNull()
    }

    @Test
    fun `fail increments attempt count`() {
        val challenge = challenge(attemptCount = 1, maxAttempts = 3)

        val failed = challenge.fail("invalid otp", now)

        assertThat(failed.attemptCount).isEqualTo(2)
        assertThat(failed.status).isEqualTo(ScaStatus.PENDING)
        assertThat(failed.failedAt).isNull()
    }

    @Test
    fun `canAttempt returns false when challenge is expired`() {
        val challenge = challenge(expiresAt = now.minusSeconds(1))

        assertThat(challenge.canAttempt(now)).isFalse()
    }

    @Test
    fun `canAttempt returns false when status is FAILED`() {
        val challenge = challenge(status = ScaStatus.FAILED)

        assertThat(challenge.canAttempt(now)).isFalse()
    }

    @Test
    fun `canAttempt returns false when status is COMPLETED`() {
        val challenge = challenge(status = ScaStatus.COMPLETED)

        assertThat(challenge.canAttempt(now)).isFalse()
    }

    @Test
    fun `isCompleted returns true only for COMPLETED status`() {
        assertThat(challenge(status = ScaStatus.COMPLETED).isCompleted()).isTrue()
        assertThat(challenge(status = ScaStatus.PENDING).isCompleted()).isFalse()
        assertThat(challenge(status = ScaStatus.FAILED).isCompleted()).isFalse()
    }

    @Test
    fun `fail sets FAILED status when max attempts reached`() {
        val challenge = challenge(attemptCount = 2, maxAttempts = 3)

        val failed = challenge.fail("invalid otp", now)

        assertThat(failed.attemptCount).isEqualTo(3)
        assertThat(failed.status).isEqualTo(ScaStatus.FAILED)
        assertThat(failed.failedAt).isNotNull()
        assertThat(failed.failureReason).isEqualTo("invalid otp")
    }

    private fun challenge(
        expiresAt: OffsetDateTime = now.plusMinutes(5),
        attemptCount: Int = 0,
        maxAttempts: Int = 3,
        status: ScaStatus = ScaStatus.PENDING,
    ) = ScaChallenge(
        id = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        purpose = ScaPurpose.LOGIN,
        method = ScaMethod.TOTP,
        status = status,
        expiresAt = expiresAt,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        createdAt = now.minusMinutes(1),
    )
}
