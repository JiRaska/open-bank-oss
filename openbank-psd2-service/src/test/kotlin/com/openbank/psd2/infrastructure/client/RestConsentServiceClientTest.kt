// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The real consent-service adapter (issue #1500) must map the REST contract faithfully and, above
 * all, must return the provider's actual `valid` verdict — the bug this closes is the stub's
 * unconditional `true`. Create/revoke stay delegated to the stub (documented follow-up).
 */
class RestConsentServiceClientTest {

    private val rest = mockk<ConsentServiceRestClient>()
    private val adapter = RestConsentServiceClient(rest, StubConsentServiceClient())

    @Test
    fun `getConsent maps id, partyId and status from the REST response`(): Unit = runBlocking {
        every { rest.getById("consent-1") } returns
            Uni.createFrom().item(ConsentRestResponse(id = "consent-1", partyId = "party-9", status = "ACTIVE"))

        val snapshot = adapter.getConsent("consent-1")

        assertThat(snapshot.consentId).isEqualTo("consent-1")
        assertThat(snapshot.partyId).isEqualTo("party-9")
        assertThat(snapshot.status).isEqualTo("ACTIVE")
    }

    @Test
    fun `getConsentStatus returns the provider status, not a hardcoded ACTIVE`(): Unit = runBlocking {
        every { rest.getById("consent-2") } returns
            Uni.createFrom().item(ConsentRestResponse(id = "consent-2", partyId = "party-9", status = "REVOKED"))

        assertThat(adapter.getConsentStatus("consent-2")).isEqualTo("REVOKED")
    }

    @Test
    fun `validateConsent returns the provider verdict true`(): Unit = runBlocking {
        every { rest.validate("consent-1", any()) } returns
            Uni.createFrom().item(ConsentValidationRestResponse(valid = true, reason = null, code = null))

        assertThat(adapter.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null)).isTrue()
    }

    @Test
    fun `validateConsent returns false when the provider denies — the stub always returned true`(): Unit = runBlocking {
        every { rest.validate("consent-1", any()) } returns
            Uni.createFrom().item(
                ConsentValidationRestResponse(valid = false, reason = "not active", code = "CONSENT_NOT_ACTIVE"),
            )

        assertThat(adapter.validateConsent("consent-1", "tpp-1", "ACCOUNTS_READ", null)).isFalse()
    }

    @Test
    fun `validateConsent forwards granteeId, scope and iban to the provider request body`(): Unit = runBlocking {
        val body = slot<ValidateConsentRestRequest>()
        every { rest.validate("consent-1", capture(body)) } returns
            Uni.createFrom().item(ConsentValidationRestResponse(valid = true, reason = null, code = null))

        adapter.validateConsent("consent-1", "tpp-7", "BALANCES_READ", "CZ6508000000192000145399")

        assertThat(body.captured.granteeId).isEqualTo("tpp-7")
        assertThat(body.captured.requiredScope).isEqualTo("BALANCES_READ")
        assertThat(body.captured.accountIban).isEqualTo("CZ6508000000192000145399")
    }
}
