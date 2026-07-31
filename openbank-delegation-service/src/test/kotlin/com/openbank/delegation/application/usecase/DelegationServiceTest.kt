// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CheckDelegationCommand
import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

class DelegationServiceTest {

    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val eligibilityClient: PartyEligibilityClient = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: DelegationService

    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service = DelegationService(repository, scaClient, eligibilityClient, clock)
    }

    private fun scaOk(partyId: UUID, purpose: String) {
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = partyId,
            purpose = purpose,
            status = "COMPLETED",
        )
    }

    private fun eligibilityOk(grantorKyc: String = "FULL", granteeKyc: String = "FULL") {
        coEvery { eligibilityClient.eligibilityOf(grantor) } returns PartyEligibility(grantor, true, grantorKyc)
        coEvery { eligibilityClient.eligibilityOf(grantee) } returns PartyEligibility(grantee, true, granteeKyc)
    }

    private fun offerCommand(
        capabilities: Set<DelegationCapability> = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
    ) = OfferDelegationCommand(
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        validTo = now.plusDays(30),
        grantScaSessionId = UUID.randomUUID(),
    )

    @Test
    fun `offer persists OFFERED grant and emits DelegationOffered`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val grant = service.offer(offerCommand())

        assertThat(grant.status).isEqualTo(DelegationStatus.OFFERED)
        assertThat(grant.grantorPartyId).isEqualTo(grantor)
        coVerify { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `offer rejects mismatched SCA purpose`(): Unit = runBlocking {
        scaOk(grantor, "CONSENT_GRANT")
        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationScaException::class.java)
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `offer rejects SCA completed by a different party`(): Unit = runBlocking {
        scaOk(UUID.randomUUID(), "DELEGATION_GRANT")
        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationScaException::class.java)
    }

    @Test
    fun `offer requires FULL kyc for execution capabilities`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk(granteeKyc = "BASIC")
        assertThatThrownBy {
            runBlocking { service.offer(offerCommand(setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT))) }
        }.isInstanceOf(DelegationEligibilityException::class.java)
            .hasMessageContaining("FULL")
    }

    @Test
    fun `offer accepts BASIC kyc for read-only capabilities`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk(granteeKyc = "BASIC")
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val grant = service.offer(offerCommand(setOf(DelegationCapability.ACCOUNT_READ_BALANCES)))

        assertThat(grant.status).isEqualTo(DelegationStatus.OFFERED)
    }

    @Test
    fun `offer rejects inactive grantee party`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        coEvery { eligibilityClient.eligibilityOf(grantor) } returns PartyEligibility(grantor, true, "FULL")
        coEvery { eligibilityClient.eligibilityOf(grantee) } returns PartyEligibility(grantee, false, "FULL")
        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationEligibilityException::class.java)
    }

    @Test
    fun `accept activates and emits DelegationActivated`(): Unit = runBlocking {
        val offered = offeredGrant()
        coEvery { repository.findById(offered.id) } returns offered
        scaOk(grantee, "DELEGATION_ACCEPT")
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val accepted = service.accept(offered.id, grantee, UUID.randomUUID())

        assertThat(accepted.status).isEqualTo(DelegationStatus.ACTIVE)
    }

    @Test
    fun `accept by a non-grantee is forbidden`(): Unit = runBlocking {
        val offered = offeredGrant()
        coEvery { repository.findById(offered.id) } returns offered

        assertThatThrownBy { runBlocking { service.accept(offered.id, UUID.randomUUID(), UUID.randomUUID()) } }
            .isInstanceOf(DelegationNotGranteeException::class.java)
    }

    @Test
    fun `revoke emits DelegationRevoked with reason`(): Unit = runBlocking {
        val active = offeredGrant().accept(UUID.randomUUID(), now)
        coEvery { repository.findById(active.id) } returns active
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val revoked = service.revoke(RevokeDelegationCommand(active.id, grantor, "enough"))

        assertThat(revoked.status).isEqualTo(DelegationStatus.REVOKED)
        assertThat(revoked.closedReason).isEqualTo("enough")
    }

    @Test
    fun `check allows a covered capability and denies an uncovered one`(): Unit = runBlocking {
        val active = offeredGrant(setOf(DelegationCapability.ACCOUNT_READ_BALANCES)).accept(UUID.randomUUID(), now)
        coEvery {
            repository.findActiveByGranteeAndResource(grantee, DelegationResourceType.ACCOUNT, accountId)
        } returns listOf(active)

        val allowed = service.check(
            CheckDelegationCommand(
                grantee,
                DelegationResourceType.ACCOUNT,
                accountId,
                DelegationCapability.ACCOUNT_READ_BALANCES,
            ),
        )
        assertThat(allowed).isInstanceOf(DelegationCheckResult.Allowed::class.java)

        val denied = service.check(
            CheckDelegationCommand(
                grantee,
                DelegationResourceType.ACCOUNT,
                accountId,
                DelegationCapability.ACCOUNT_INITIATE_PAYMENT,
            ),
        )
        assertThat(denied).isInstanceOf(DelegationCheckResult.Denied::class.java)
    }

    @Test
    fun `check denies when the grant exists but is not active`(): Unit = runBlocking {
        val offered = offeredGrant(setOf(DelegationCapability.ACCOUNT_READ_BALANCES))
        coEvery {
            repository.findActiveByGranteeAndResource(grantee, DelegationResourceType.ACCOUNT, accountId)
        } returns listOf(offered)

        val result = service.check(
            CheckDelegationCommand(
                grantee,
                DelegationResourceType.ACCOUNT,
                accountId,
                DelegationCapability.ACCOUNT_READ_BALANCES,
            ),
        )
        assertThat(result).isInstanceOf(DelegationCheckResult.Denied::class.java)
    }

    private fun offeredGrant(
        capabilities: Set<DelegationCapability> = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
    ) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        validFrom = now,
        validTo = now.plusDays(30),
        status = DelegationStatus.OFFERED,
        createdAt = now,
        updatedAt = now,
    )
}
