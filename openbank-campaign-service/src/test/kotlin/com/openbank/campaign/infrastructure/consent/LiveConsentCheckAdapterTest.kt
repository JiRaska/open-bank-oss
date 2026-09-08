// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.consent

import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The consent grantee must follow the SCOPE, not the caller.
 *
 * customer-edge writes the ADR-0269 credit consents under the bank grantee (`openbank`), because
 * that is who the customer grants them to. This adapter asked for every scope under the marketing
 * grantee, so the CREDIT_OFFERS lookup queried a (party, grantee, scope) triple that is never
 * written. The rule 1 enrolment gate could not see the switch the customer flips; it failed
 * closed, so it read as a credit campaign enrolling nobody rather than as an error.
 *
 * These tests assert the grantee actually SENT to consent-service, because that is the thing that
 * was wrong. Asserting only the boolean would have passed throughout the defect.
 */
class LiveConsentCheckAdapterTest {

    private val asked = mutableListOf<Triple<UUID, String, String>>()

    private val client = object : ConsentServiceClient {
        override fun hasActiveConsent(partyId: UUID, granteeId: String, scope: String): Uni<ConsentCheckResponse> {
            asked += Triple(partyId, granteeId, scope)
            // Granted only for the triple customer-edge actually writes.
            val real = granteeId == BANK && scope.startsWith("CREDIT_")
            return Uni.createFrom().item(ConsentCheckResponse(granted = real || granteeId == MARKETING))
        }
    }

    private val adapter = LiveConsentCheckAdapter(client, MARKETING, BANK)

    @Test
    fun `credit scopes are asked under the bank grantee`() = runBlocking<Unit> {
        val party = UUID.randomUUID()
        val granted = adapter.hasActiveConsent(party, "CREDIT_OFFERS")

        assertThat(asked).singleElement()
            .describedAs("CREDIT_OFFERS must be looked up under the grantee customer-edge writes")
            .isEqualTo(Triple(party, BANK, "CREDIT_OFFERS"))
        assertThat(granted)
            .describedAs("a consent the customer actually holds must read as granted")
            .isTrue()
    }

    @Test
    fun `marketing scopes keep the marketing grantee`() = runBlocking<Unit> {
        val party = UUID.randomUUID()
        adapter.hasActiveConsent(party, "MARKETING_EMAIL")

        assertThat(asked).singleElement()
            .describedAs("non-credit scopes must not be moved onto the bank grantee")
            .isEqualTo(Triple(party, MARKETING, "MARKETING_EMAIL"))
    }

    private companion object {
        const val MARKETING = "party-service:marketing-comms"
        const val BANK = "openbank"
    }
}
