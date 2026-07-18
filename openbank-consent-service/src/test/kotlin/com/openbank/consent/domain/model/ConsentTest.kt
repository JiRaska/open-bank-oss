// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID

class ConsentTest {

    private val partyId = UUID.randomUUID()
    private val baseValidFrom = OffsetDateTime.now().plusMinutes(1)

    @Test
    fun `isActive returns true for ACTIVE consent before validTo`() {
        val consent = consent(status = ConsentStatus.ACTIVE, validTo = baseValidFrom.plusDays(1))

        assertThat(consent.isActive(OffsetDateTime.now())).isTrue()
    }

    @Test
    fun `isActive returns false for REVOKED consent`() {
        val consent = consent(status = ConsentStatus.REVOKED, validTo = baseValidFrom.plusDays(1))

        assertThat(consent.isActive(OffsetDateTime.now())).isFalse()
    }

    @Test
    fun `hasScope checks membership`() {
        val consent = consent(scopes = setOf(ConsentScope.ACCOUNTS_READ, ConsentScope.BALANCES_READ))

        assertThat(consent.hasScope(ConsentScope.ACCOUNTS_READ)).isTrue()
        assertThat(consent.hasScope(ConsentScope.TRANSACTIONS_READ)).isFalse()
    }

    @Test
    fun `coversAccount returns true when accountIbans is null`() {
        val consent = consent(accountIbans = null)

        assertThat(consent.coversAccount("CZ6508000000192000145399")).isTrue()
    }

    @Test
    fun `coversAccount returns false when IBAN not in list`() {
        val consent = consent(accountIbans = listOf("CZ6508000000192000145399"))

        assertThat(consent.coversAccount("CZ7108000000192000145399")).isFalse()
    }

    @Test
    fun `revoke sets status to REVOKED`() {
        val consent = consent()

        assertThat(consent.revoke("customer request", OffsetDateTime.now()).status).isEqualTo(ConsentStatus.REVOKED)
    }

    @Test
    fun `activate sets status to ACTIVE with scaSessionId`() {
        val scaSessionId = UUID.randomUUID()

        val activated = consent(status = ConsentStatus.PENDING_SCA).activate(scaSessionId, OffsetDateTime.now())

        assertThat(activated.status).isEqualTo(ConsentStatus.ACTIVE)
        assertThat(activated.scaSessionId).isEqualTo(scaSessionId)
    }

    @Test
    fun `reject sets status to REJECTED`() {
        val rejected = consent(status = ConsentStatus.PENDING_SCA).reject(OffsetDateTime.now())

        assertThat(rejected.status).isEqualTo(ConsentStatus.REJECTED)
    }

    @Test
    fun `init validation rejects empty scopes`() {
        assertThrows<IllegalArgumentException> {
            consent(scopes = emptySet())
        }
    }

    @Test
    fun `init validation rejects validTo before validFrom`() {
        assertThrows<IllegalArgumentException> {
            consent(validFrom = baseValidFrom, validTo = baseValidFrom.minusDays(1))
        }
    }

    @Test
    fun `init validation rejects AISP scopes beyond 90 days`() {
        assertThrows<IllegalArgumentException> {
            consent(
                scopes = setOf(ConsentScope.ACCOUNTS_READ),
                validFrom = baseValidFrom,
                validTo = baseValidFrom.plusDays(91),
            )
        }
    }

    @Test
    fun `TELEMETRY_RUM consent is allowed past 90 days (GDPR, not AISP-capped)`() {
        val consent = consent(
            scopes = setOf(ConsentScope.TELEMETRY_RUM),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )

        assertThat(consent.hasScope(ConsentScope.TELEMETRY_RUM)).isTrue()
        assertThat(consent.isActive(OffsetDateTime.now())).isTrue()
    }

    @Test
    fun `TELEMETRY_RUM consent is still capped at the 365-day maximum`() {
        assertThrows<IllegalArgumentException> {
            consent(
                scopes = setOf(ConsentScope.TELEMETRY_RUM),
                accountIbans = null,
                validFrom = baseValidFrom,
                validTo = baseValidFrom.plusDays(366),
            )
        }
    }

    @Test
    fun `frequencyPerDay returns the PSD2 AISP cap for AISP scopes`() {
        val consent = consent(scopes = setOf(ConsentScope.ACCOUNTS_READ))

        assertThat(consent.frequencyPerDay()).isEqualTo(Consent.AISP_MAX_ACCESSES_PER_DAY)
    }

    @Test
    fun `frequencyPerDay is null for non-AISP scopes`() {
        val consent = consent(
            scopes = setOf(ConsentScope.TELEMETRY_RUM),
            accountIbans = null,
            validFrom = baseValidFrom,
            validTo = baseValidFrom.plusDays(180),
        )

        assertThat(consent.frequencyPerDay()).isNull()
    }

    private fun consent(
        scopes: Set<ConsentScope> = setOf(ConsentScope.PAYMENTS_INITIATE),
        accountIbans: List<String>? = listOf("CZ6508000000192000145399"),
        status: ConsentStatus = ConsentStatus.ACTIVE,
        validFrom: OffsetDateTime = baseValidFrom,
        validTo: OffsetDateTime = baseValidFrom.plusDays(1),
    ): Consent = Consent(
        id = UUID.randomUUID(),
        partyId = partyId,
        granteeId = "tpp-123",
        granteeType = GranteeType.TPP,
        granteeName = "Test TPP",
        scopes = scopes,
        accountIbans = accountIbans,
        status = status,
        validFrom = validFrom,
        validTo = validTo,
        scaSessionId = null,
        redirectUri = "https://example.com/redirect",
        tppTransactionId = "txn-1",
        ipAddress = "127.0.0.1",
        userAgent = "JUnit",
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )
}
