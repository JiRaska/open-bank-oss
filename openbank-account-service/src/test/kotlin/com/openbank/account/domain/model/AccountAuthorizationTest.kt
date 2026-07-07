// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class AccountAuthorizationTest {

    private val validFrom = LocalDate.of(2024, 1, 10)
    private val validTo = LocalDate.of(2024, 6, 30)

    private fun authorization(
        status: AuthorizationStatus = AuthorizationStatus.ACTIVE,
        validTo: LocalDate? = this.validTo,
    ) = AccountAuthorization(
        accountId = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        role = AuthorizationRole.PAYMENT_ONLY,
        dailyLimit = null,
        transactionLimit = null,
        validFrom = validFrom,
        validTo = validTo,
        status = status,
        grantedBy = UUID.randomUUID(),
        grantedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    @Test
    fun `isActiveOn is inclusive of both validity bounds`() {
        val auth = authorization()

        assertThat(auth.isActiveOn(validFrom)).isTrue()
        assertThat(auth.isActiveOn(validTo)).isTrue()
        assertThat(auth.isActiveOn(validFrom.minusDays(1))).isFalse()
        assertThat(auth.isActiveOn(validTo.plusDays(1))).isFalse()
    }

    @Test
    fun `isActiveOn treats a null validTo as open-ended`() {
        val auth = authorization(validTo = null)

        assertThat(auth.isActiveOn(LocalDate.of(2099, 12, 31))).isTrue()
    }

    @Test
    fun `isActiveOn is false for any non-ACTIVE status even inside the window`() {
        val inWindow = LocalDate.of(2024, 3, 1)

        assertThat(authorization(status = AuthorizationStatus.SUSPENDED).isActiveOn(inWindow)).isFalse()
        assertThat(authorization(status = AuthorizationStatus.REVOKED).isActiveOn(inWindow)).isFalse()
        assertThat(authorization(status = AuthorizationStatus.EXPIRED).isActiveOn(inWindow)).isFalse()
    }

    @Test
    fun `revoke records who, why and the clock instant`() {
        val revokedBy = UUID.randomUUID()
        val fixedInstant = Instant.parse("2024-04-01T09:30:00Z")

        val revoked = authorization().revoke(revokedBy, "mandate withdrawn", Clock.fixed(fixedInstant, ZoneOffset.UTC))

        assertThat(revoked.status).isEqualTo(AuthorizationStatus.REVOKED)
        assertThat(revoked.revokedBy).isEqualTo(revokedBy)
        assertThat(revoked.revokedReason).isEqualTo("mandate withdrawn")
        assertThat(revoked.revokedAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `suspend and reinstate round-trip the status`() {
        val suspended = authorization().suspend()
        assertThat(suspended.status).isEqualTo(AuthorizationStatus.SUSPENDED)

        val reinstated = suspended.reinstate()
        assertThat(reinstated.status).isEqualTo(AuthorizationStatus.ACTIVE)
    }
}
