// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationResourceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Recertification audience: which review desk owns a grant's periodic re-approval, and which
 * grantor party types may claim each one.
 *
 * Split out of `DelegationServiceTest` rather than added to it. That class is at detekt's
 * `LargeClass` bound on `main`, so any delegation PR adding a case to it goes red — #9522 hit the
 * identical wall with a different addition. The split is also the right shape here: these are the
 * only cases that vary the grantor's `partyType`, which every other test leaves at its default.
 */
class DelegationRecertificationAudienceTest {

    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val eligibilityClient: PartyEligibilityClient = mockk()
    private val ownershipClient: ResourceOwnershipClient = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: DelegationService

    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service = DelegationService(repository, scaClient, eligibilityClient, ownershipClient, clock)
        coEvery { ownershipClient.verifyOwnership(grantor, any(), any()) } returns OwnershipVerdict.OWNED
        coEvery { scaClient.consumeChallenge(any(), any()) } answers {
            ScaChallengeSnapshot(firstArg(), secondArg(), "DELEGATION_GRANT", "COMPLETED")
        }
    }

    @Test
    fun `company recertification audience is explicit and persisted with the offered grant`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        coEvery { eligibilityClient.eligibilityOf(grantor) } returns
            PartyEligibility(grantor, true, "FULL", partyType = "COMPANY")
        coEvery { eligibilityClient.eligibilityOf(grantee) } returns PartyEligibility(grantee, true, "FULL")
        val saved = slot<DelegationGrant>()
        coEvery { repository.save(capture(saved), any()) } answers { firstArg() }

        service.offer(offerCommand().copy(recertificationAudience = DelegationRecertificationAudience.CORPORATE))

        assertThat(saved.captured.recertificationAudience).isEqualTo(DelegationRecertificationAudience.CORPORATE)
    }

    @Test
    fun `company review audience is refused for a sole trader before SCA is consumed`() {
        coEvery { eligibilityClient.eligibilityOf(grantor) } returns
            PartyEligibility(grantor, true, "FULL", partyType = "SOLE_TRADER")
        coEvery { eligibilityClient.eligibilityOf(grantee) } returns PartyEligibility(grantee, true, "FULL")

        assertThatThrownBy {
            runBlocking {
                service.offer(offerCommand().copy(recertificationAudience = DelegationRecertificationAudience.SME))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    private fun scaOk(partyId: UUID, purpose: String) {
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = partyId,
            purpose = purpose,
            status = "COMPLETED",
        )
    }

    private fun offerCommand(
        capabilities: Set<DelegationCapability> = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
    ) = OfferDelegationCommand(
        callerPartyId = grantor,
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        validTo = now.plusDays(30),
        grantScaSessionId = UUID.randomUUID(),
    )
}
