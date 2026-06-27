// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest.dto

import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.GranteeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ConsentResponseTest {

    @Test
    fun `from projects every exposed field of the consent`() {
        val consent = consent()

        val response = ConsentResponse.from(consent)

        assertThat(response.id).isEqualTo(consent.id)
        assertThat(response.partyId).isEqualTo(consent.partyId)
        assertThat(response.granteeId).isEqualTo(consent.granteeId)
        assertThat(response.granteeType).isEqualTo(consent.granteeType)
        assertThat(response.granteeName).isEqualTo(consent.granteeName)
        assertThat(response.scopes).isEqualTo(consent.scopes)
        assertThat(response.accountIbans).isEqualTo(consent.accountIbans)
        assertThat(response.status).isEqualTo(consent.status)
        assertThat(response.validFrom).isEqualTo(consent.validFrom)
        assertThat(response.validTo).isEqualTo(consent.validTo)
        assertThat(response.createdAt).isEqualTo(consent.createdAt)
    }

    @Test
    fun `from carries null accountIbans through unchanged`() {
        val consent = consent(accountIbans = null)

        val response = ConsentResponse.from(consent)

        assertThat(response.accountIbans).isNull()
    }

    private fun consent(accountIbans: List<String>? = listOf("CZ6508000000192000145399")): Consent {
        val validFrom = OffsetDateTime.now().plusMinutes(1)
        return Consent(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-123",
            granteeType = GranteeType.TPP,
            granteeName = "Test TPP",
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            accountIbans = accountIbans,
            status = ConsentStatus.ACTIVE,
            validFrom = validFrom,
            validTo = validFrom.plusDays(1),
            scaSessionId = UUID.randomUUID(),
            redirectUri = "https://example.com/redirect",
            tppTransactionId = "txn-1",
            ipAddress = "127.0.0.1",
            userAgent = "JUnit",
            createdAt = validFrom,
            updatedAt = validFrom,
        )
    }
}
