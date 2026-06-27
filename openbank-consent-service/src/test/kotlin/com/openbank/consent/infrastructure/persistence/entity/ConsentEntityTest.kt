// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.persistence.entity

import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.GranteeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ConsentEntityTest {

    @Test
    fun `fromDomain then toDomain round-trips a fully-populated consent`() {
        val original = consent()

        val roundTripped = ConsentEntity.fromDomain(original).toDomain()

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `fromDomain then toDomain round-trips a consent with null optionals`() {
        val original = consent(
            accountIbans = null,
            scaSessionId = null,
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
            userAgent = null,
            revokedAt = null,
            revokedReason = null,
        )

        val roundTripped = ConsentEntity.fromDomain(original).toDomain()

        assertThat(roundTripped).isEqualTo(original)
        assertThat(roundTripped.accountIbans).isNull()
        assertThat(roundTripped.revokedAt).isNull()
    }

    @Test
    fun `fromDomain copies every scalar field onto the entity accessors`() {
        val original = consent()

        val entity = ConsentEntity.fromDomain(original)

        assertThat(entity.id).isEqualTo(original.id)
        assertThat(entity.partyId).isEqualTo(original.partyId)
        assertThat(entity.granteeId).isEqualTo(original.granteeId)
        assertThat(entity.granteeType).isEqualTo(original.granteeType)
        assertThat(entity.granteeName).isEqualTo(original.granteeName)
        assertThat(entity.scopes).containsExactlyInAnyOrderElementsOf(original.scopes)
        assertThat(entity.accountIbans).isEqualTo(original.accountIbans)
        assertThat(entity.status).isEqualTo(original.status)
        assertThat(entity.validFrom).isEqualTo(original.validFrom)
        assertThat(entity.validTo).isEqualTo(original.validTo)
        assertThat(entity.scaSessionId).isEqualTo(original.scaSessionId)
        assertThat(entity.redirectUri).isEqualTo(original.redirectUri)
        assertThat(entity.tppTransactionId).isEqualTo(original.tppTransactionId)
        assertThat(entity.ipAddress).isEqualTo(original.ipAddress)
        assertThat(entity.userAgent).isEqualTo(original.userAgent)
        assertThat(entity.createdAt).isEqualTo(original.createdAt)
        assertThat(entity.updatedAt).isEqualTo(original.updatedAt)
        assertThat(entity.revokedAt).isEqualTo(original.revokedAt)
        assertThat(entity.revokedReason).isEqualTo(original.revokedReason)
    }

    private fun consent(
        accountIbans: List<String>? = listOf("CZ6508000000192000145399", "CZ1010000000001234567890"),
        scaSessionId: UUID? = UUID.randomUUID(),
        redirectUri: String? = "https://example.com/redirect",
        tppTransactionId: String? = "txn-1",
        ipAddress: String? = "127.0.0.1",
        userAgent: String? = "JUnit",
        revokedAt: OffsetDateTime? = OffsetDateTime.now().plusDays(5),
        revokedReason: String? = "customer request",
    ): Consent {
        val validFrom = OffsetDateTime.now().plusMinutes(1)
        return Consent(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-123",
            granteeType = GranteeType.TPP,
            granteeName = "Test TPP",
            scopes = setOf(ConsentScope.ACCOUNTS_READ, ConsentScope.BALANCES_READ),
            accountIbans = accountIbans,
            status = ConsentStatus.ACTIVE,
            validFrom = validFrom,
            validTo = validFrom.plusDays(1),
            scaSessionId = scaSessionId,
            redirectUri = redirectUri,
            tppTransactionId = tppTransactionId,
            ipAddress = ipAddress,
            userAgent = userAgent,
            createdAt = validFrom,
            updatedAt = validFrom,
            revokedAt = revokedAt,
            revokedReason = revokedReason,
        )
    }
}
