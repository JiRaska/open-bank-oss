// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.out.ApprovalGroupRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.model.ApprovalGroup
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
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

/** N_OF_M admission (#9430): split from DelegationServiceTest, which is at detekt's LargeClass limit. */
class DelegationNOfMAdmissionTest {

    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val eligibilityClient: PartyEligibilityClient = mockk()
    private val authorityClient: GrantorAuthorityClient = mockk()
    private val ownershipClient: ResourceOwnershipClient = mockk()
    private val approvalGroupRepository: ApprovalGroupRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)
    private lateinit var service: DelegationService

    @BeforeEach
    fun setUp() {
        service = DelegationService(
            repository,
            scaClient,
            eligibilityClient,
            authorityClient,
            ownershipClient,
            approvalGroupRepository,
            true,
            clock,
        )
        coEvery { authorityClient.authorityFor(grantor, grantor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED)
        coEvery { ownershipClient.verifyOwnership(grantor, any(), any()) } returns OwnershipVerdict.OWNED
        coEvery { scaClient.consumeChallenge(any(), any(), any()) } answers {
            ScaChallengeSnapshot(firstArg(), secondArg(), "DELEGATION_GRANT", "COMPLETED")
        }
        coEvery { scaClient.getChallenge(any()) } returns
            ScaChallengeSnapshot(UUID.randomUUID(), grantor, "DELEGATION_GRANT", "COMPLETED")
        coEvery { eligibilityClient.eligibilityOf(grantee) } returns PartyEligibility(grantee, true, "FULL", null)
    }

    private fun scaOk(@Suppress("UNUSED_PARAMETER") party: UUID, @Suppress("UNUSED_PARAMETER") purpose: String) = Unit

    private fun eligibilityOk() = Unit

    private fun offerCommand() = OfferDelegationCommand(
        callerPartyId = grantor,
        actorPartyId = grantor,
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validTo = now.plusDays(30),
        grantScaSessionId = UUID.randomUUID(),
    )

    /**
     * ADR-0232 D8 promises `approvalPolicy` binds per-resource co-signing — "oba rodiče musí
     * schválit výběr". It binds nothing. The field is accepted, validated for self-consistency
     * (N_OF_M demands requiredApprovals >= 2), persisted, echoed and rendered in admin-ui, and
     * read by no decision anywhere: `DelegationGrant.covers` consults capability and
     * perTransactionLimit only, `DelegationOffered` does not carry it, and the account-service
     * projection has no column for it — so account-service's `SavingsProposalService.decide`
     * releases the money on a SINGLE owner decision whatever the policy said. Same shape as the
     * cumulative ceilings: present at every layer except the enforcing one.
     */
    @Test
    fun `offer derives N_OF_M threshold and revision from the owned active approval group`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        val groupId = UUID.randomUUID()
        coEvery { approvalGroupRepository.findById(groupId) } returns ApprovalGroup(
            id = groupId,
            ownerPartyId = grantor,
            name = "Family",
            members = setOf(UUID.randomUUID(), UUID.randomUUID()),
            threshold = 2,
            revision = 7,
            lastScaSessionId = UUID.randomUUID(),
            createdAt = now,
            updatedAt = now,
        )
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val grant = service.offer(
            offerCommand().copy(
                resourceType = DelegationResourceType.SAVINGS_GOAL,
                capabilities = setOf(DelegationCapability.SAVINGS_PROPOSE_WITHDRAW),
                approvalPolicy = ApprovalPolicy.N_OF_M,
                approvalGroupId = groupId,
            ),
        )

        assertThat(grant.requiredApprovals).isEqualTo(2)
        assertThat(grant.approvalGroupId).isEqualTo(groupId)
        assertThat(grant.approvalGroupRevision).isEqualTo(7)
        coVerify { scaClient.consumeChallenge(grant.grantScaSessionId!!, grantor, match { it.isNotBlank() }) }
    }

    /** `openbank.delegation.n-of-m-enabled` defaults to false: main's refusal stands until an operator opts in. */
    @Test
    fun `offer refuses N_OF_M while the admission flag is off, before spending SCA`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        val flagOff = DelegationService(
            repository,
            scaClient,
            eligibilityClient,
            authorityClient,
            ownershipClient,
            approvalGroupRepository,
            false,
            clock,
        )

        assertThatThrownBy {
            runBlocking {
                flagOff.offer(
                    offerCommand().copy(
                        resourceType = DelegationResourceType.SAVINGS_GOAL,
                        capabilities = setOf(DelegationCapability.SAVINGS_PROPOSE_WITHDRAW),
                        approvalPolicy = ApprovalPolicy.N_OF_M,
                        approvalGroupId = UUID.randomUUID(),
                    ),
                )
            }
        }
            .isInstanceOf(DelegationUnsupportedConstraintException::class.java)
            .hasMessageContaining("not enabled")

        coVerify(exactly = 0) { approvalGroupRepository.findById(any()) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }
}
