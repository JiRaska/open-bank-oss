// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest.dto

import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.ConsentValidationResult
import com.openbank.consent.domain.model.GranteeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class ConsentValidationResponseTest {

    @Test
    fun `from Valid projects scopes, grantedAccounts and the AISP frequency cap`() {
        val consent = consent(
            scopes = setOf(ConsentScope.ACCOUNTS_READ, ConsentScope.BALANCES_READ),
            accountIbans = listOf("CZ6508000000192000145399"),
        )

        val response = ConsentValidationResponse.from(ConsentValidationResult.Valid(consent))

        assertThat(response.valid).isTrue()
        assertThat(response.reason).isNull()
        assertThat(response.code).isNull()
        assertThat(response.scopes).isEqualTo(consent.scopes)
        assertThat(response.grantedAccounts).isEqualTo(consent.accountIbans)
        assertThat(response.frequencyPerDay).isEqualTo(Consent.AISP_MAX_ACCESSES_PER_DAY)
    }

    @Test
    fun `from Valid with an all-accounts non-AISP consent leaves grantedAccounts and frequencyPerDay null`() {
        val consent = consent(scopes = setOf(ConsentScope.TELEMETRY_RUM), accountIbans = null)

        val response = ConsentValidationResponse.from(ConsentValidationResult.Valid(consent))

        assertThat(response.valid).isTrue()
        assertThat(response.scopes).isEqualTo(setOf(ConsentScope.TELEMETRY_RUM))
        assertThat(response.grantedAccounts).isNull()
        assertThat(response.frequencyPerDay).isNull()
    }

    @Test
    fun `from Invalid carries reason and code and no projection fields`() {
        val response = ConsentValidationResponse.from(
            ConsentValidationResult.Invalid("Consent is not active (status=REVOKED)", "CONSENT_NOT_ACTIVE"),
        )

        assertThat(response.valid).isFalse()
        assertThat(response.reason).isEqualTo("Consent is not active (status=REVOKED)")
        assertThat(response.code).isEqualTo("CONSENT_NOT_ACTIVE")
        assertThat(response.scopes).isNull()
        assertThat(response.grantedAccounts).isNull()
        assertThat(response.frequencyPerDay).isNull()
    }

    private fun consent(scopes: Set<ConsentScope>, accountIbans: List<String>?): Consent {
        val now = OffsetDateTime.now()
        return Consent(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            granteeId = "tpp-123",
            granteeType = GranteeType.TPP,
            granteeName = "Test TPP",
            scopes = scopes,
            accountIbans = accountIbans,
            status = ConsentStatus.ACTIVE,
            validFrom = now,
            validTo = now.plusDays(30),
            scaSessionId = null,
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
            userAgent = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
