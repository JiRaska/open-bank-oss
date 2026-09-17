// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationOrganizationAcceptanceTest {
    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val authorityClient: GrantorAuthorityClient = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val session = UUID.randomUUID()
    private val now = OffsetDateTime.now(clock)
    private val offered = DelegationGrant(
        grantorPartyId = UUID.randomUUID(),
        granteePartyId = company,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.randomUUID(),
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validFrom = now,
        validTo = now.plusDays(30),
        status = DelegationStatus.OFFERED,
        createdAt = now,
        updatedAt = now,
    )
    private val service = DelegationService(
        repository,
        scaClient,
        mockk<PartyEligibilityClient>(),
        authorityClient,
        mockk<ResourceOwnershipClient>(),
        clock,
    )

    @Test
    fun `sole representative accepts for company with personal SCA`(): Unit = runBlocking {
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(company, human) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, partyType = "COMPANY")
        coEvery { scaClient.getChallenge(session) } returns
            ScaChallengeSnapshot(session, human, "DELEGATION_ACCEPT", "COMPLETED")
        coEvery { scaClient.consumeChallenge(session, human) } returns
            ScaChallengeSnapshot(session, human, "DELEGATION_ACCEPT", "COMPLETED")
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val accepted = service.accept(offered.id, company, session, company, human)

        assertThat(accepted.status).isEqualTo(DelegationStatus.ACTIVE)
        coVerify(exactly = 1) { scaClient.consumeChallenge(session, human) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(session, company) }
    }

    @Test
    fun `joint representative cannot accept and never spends SCA`(): Unit = runBlocking {
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(company, human) } returns
            GrantorAuthority(GrantorAuthorityVerdict.DENIED, partyType = "COMPANY")

        assertThatThrownBy { runBlocking { service.accept(offered.id, company, session, company, human) } }
            .isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { scaClient.getChallenge(any()) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `company challenge cannot replace the human representative challenge`(): Unit = runBlocking {
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(company, human) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, partyType = "COMPANY")
        coEvery { scaClient.getChallenge(session) } returns
            ScaChallengeSnapshot(session, company, "DELEGATION_ACCEPT", "COMPLETED")

        assertThatThrownBy { runBlocking { service.accept(offered.id, company, session, company, human) } }
            .isInstanceOf(DelegationScaException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `an already accepted offer does not consume another SCA challenge`(): Unit = runBlocking {
        coEvery { repository.findById(offered.id) } returns offered.accept(UUID.randomUUID(), now)
        coEvery { authorityClient.authorityFor(company, human) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, partyType = "COMPANY")

        assertThatThrownBy { runBlocking { service.accept(offered.id, company, session, company, human) } }
            .isInstanceOf(IllegalStateException::class.java)

        coVerify(exactly = 0) { scaClient.getChallenge(any()) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any()) }
    }

    @Test
    fun `missing human actor cannot impersonate a company`(): Unit = runBlocking {
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(company, company) } returns
            GrantorAuthority(GrantorAuthorityVerdict.DENIED, partyType = "COMPANY")

        assertThatThrownBy { runBlocking { service.accept(offered.id, company, session, company) } }
            .isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { scaClient.getChallenge(any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }
}
