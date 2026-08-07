// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.client

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Issue #3604 — the counterparty display name that ends up on a delegation grant is composed
 * here, from the eligibility lookup this service already performs. The composition is small and
 * every one of its edge cases is a defect a customer would see on the accept screen, so each
 * gets its own case.
 *
 * The one thing this file CANNOT prove is that pid-service still serves `coreAttributes` at that
 * path with those names — a mocked rest-client answers whatever it is told. That is
 * `DelegationPartyEligibilityPactConsumerTest` plus pid-service's `@PactFolder` replay.
 */
class ResilientPartyEligibilityClientTest {

    private val rest: PidServiceRestClient = mockk()
    private val client = ResilientPartyEligibilityClient(rest)
    private val partyId: UUID = UUID.randomUUID()

    private fun pidAnswers(core: PidCoreAttributes?) {
        coEvery { rest.getParty(partyId) } returns PidPartyResponse(
            id = partyId,
            status = "ACTIVE",
            kycAttributes = PidKycAttributes("FULL"),
            coreAttributes = core,
        )
    }

    @Test
    fun `given and family name compose into one display name`(): Unit = runBlocking {
        pidAnswers(PidCoreAttributes(givenName = "Alice", familyName = "Testerova"))

        assertThat(client.eligibilityOf(partyId).displayName).isEqualTo("Alice Testerova")
    }

    @Test
    fun `a single available name part is still a usable label`(): Unit = runBlocking {
        pidAnswers(PidCoreAttributes(givenName = null, familyName = "Testerova"))

        assertThat(client.eligibilityOf(partyId).displayName).isEqualTo("Testerova")
    }

    /**
     * The whole point of returning null rather than "": the consumer renders the party id when
     * the label is absent, and an empty chip on a consent screen reads as a name that failed to
     * load. `""` would be truthy enough to pass through most fallbacks.
     */
    @Test
    fun `blank name parts yield null, never an empty label`(): Unit = runBlocking {
        pidAnswers(PidCoreAttributes(givenName = "   ", familyName = ""))

        assertThat(client.eligibilityOf(partyId).displayName).isNull()
    }

    /** An older pid-service, or a response shape change, must not break eligibility itself. */
    @Test
    fun `an absent coreAttributes object still yields a usable eligibility verdict`(): Unit = runBlocking {
        pidAnswers(null)

        val eligibility = client.eligibilityOf(partyId)

        assertThat(eligibility.displayName).isNull()
        assertThat(eligibility.active).isTrue()
        assertThat(eligibility.kycLevel).isEqualTo("FULL")
    }
}
