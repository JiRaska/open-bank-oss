// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
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
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.GrantorSigningEvidence
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

/**
 * Organization grant authority: a human ACTOR offers or previews on behalf of an organization
 * GRANTOR, and the mandate is what permits it.
 *
 * Split out of `DelegationServiceTest` rather than added to it. That class is at detekt's
 * `LargeClass` bound, so the three cases below would have tipped it over — and the split is the
 * right shape anyway: these are the only cases where `actorPartyId != grantorPartyId`, so they
 * need the authority client to answer for a DIFFERENT pair than every other test sets up.
 *
 * The fixture is deliberately duplicated rather than inherited. A shared base class would make
 * the actor/grantor distinction an inherited default, which is exactly the thing under test here.
 */
class DelegationOrganizationAuthorityTest {

    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val eligibilityClient: PartyEligibilityClient = mockk()
    private val authorityClient: GrantorAuthorityClient = mockk()
    private val ownershipClient: ResourceOwnershipClient = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: DelegationService

    /** The organization whose resource is being delegated. */
    private val grantor: UUID = UUID.randomUUID()

    /** The human acting under the organization's mandate — never the same party as the grantor. */
    private val organizationActor: UUID = UUID.randomUUID()
    private val mandateId: UUID = UUID.randomUUID()
    private val granteeActor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service = DelegationService(
            repository,
            scaClient,
            eligibilityClient,
            authorityClient,
            ownershipClient,
            mockk(),
            false,
            clock,
        )
        coEvery { authorityClient.authorityFor(grantor, grantor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED)
        coEvery { ownershipClient.verifyOwnership(grantor, any(), any()) } returns OwnershipVerdict.OWNED
        coEvery { scaClient.consumeChallenge(any(), any(), any()) } answers {
            ScaChallengeSnapshot(firstArg(), secondArg(), "DELEGATION_GRANT", "COMPLETED")
        }
    }

    @Test
    fun `organization offer requires an active mandate and spends the human actor SCA`(): Unit = runBlocking {
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, "Acme s.r.o.", soleMandateId = mandateId)
        scaOk(organizationActor, "DELEGATION_GRANT")
        eligibilityOk(granteeName = "Bob Zkousky")
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val grant = service.offer(offerCommand().copy(actorPartyId = organizationActor))

        assertThat(grant.grantorName).isEqualTo("Acme s.r.o.")
        assertThat(grant.grantorSigningEvidence).isEqualTo(GrantorSigningEvidence(organizationActor, mandateId))
        coVerify(exactly = 1) { scaClient.consumeChallenge(any(), organizationActor, any()) }
        coVerify(exactly = 0) { eligibilityClient.eligibilityOf(grantor) }
    }

    @Test
    fun `organization preview fails closed without a mandate before SCA or ownership`() {
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.DENIED)

        assertThatThrownBy {
            runBlocking { service.preview(previewCommand().copy(actorPartyId = organizationActor)) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { ownershipClient.verifyOwnership(any(), any(), any()) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `organization offer requires sole authority before consuming SCA or saving a grant`() {
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.MANDATE_ONLY)

        assertThatThrownBy {
            runBlocking { service.offer(offerCommand().copy(actorPartyId = organizationActor)) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `organization authority outage is retryable and happens before SCA or ownership`() {
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.UNVERIFIABLE)

        assertThatThrownBy {
            runBlocking { service.preview(previewCommand().copy(actorPartyId = organizationActor)) }
        }.isInstanceOf(DelegationGrantorAuthorityUnavailableException::class.java)

        coVerify(exactly = 0) { ownershipClient.verifyOwnership(any(), any(), any()) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `company grantee accepts only with the verified sole actor and that actor's SCA`(): Unit = runBlocking {
        val offered = offeredGrant(GrantorSigningEvidence(grantor, null))
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(grantee, granteeActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, soleMandateId = UUID.randomUUID())
        scaOk(granteeActor, "DELEGATION_ACCEPT")
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val result = service.accept(offered.id, grantee, UUID.randomUUID(), grantee, granteeActor)

        assertThat(result.status).isEqualTo(DelegationStatus.ACTIVE)
        coVerify(exactly = 1) { scaClient.consumeChallenge(any(), granteeActor, any()) }
    }

    @Test
    fun `company grantee with a joint mandate cannot activate a grant`() {
        val offered = offeredGrant(GrantorSigningEvidence(grantor, null))
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(grantee, granteeActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.MANDATE_ONLY)

        assertThatThrownBy {
            runBlocking { service.accept(offered.id, grantee, UUID.randomUUID(), grantee, granteeActor) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `company offer without verified issuer evidence cannot activate`() {
        val offered = offeredGrant(null)
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(grantor, grantor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.DENIED, partyType = "COMPANY")

        assertThatThrownBy {
            runBlocking { service.accept(offered.id, grantee, UUID.randomUUID(), grantee) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `company offer with verified current issuer evidence can activate`(): Unit = runBlocking {
        val offered = offeredGrant(GrantorSigningEvidence(organizationActor, mandateId))
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, soleMandateId = mandateId)
        coEvery { authorityClient.authorityFor(grantee, grantee) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED)
        scaOk(grantee, "DELEGATION_ACCEPT")
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val result = service.accept(offered.id, grantee, UUID.randomUUID(), grantee)

        assertThat(result.status).isEqualTo(DelegationStatus.ACTIVE)
        coVerify(exactly = 1) { scaClient.consumeChallenge(any(), grantee, any()) }
    }

    @Test
    fun `company offer with changed issuer mandate cannot activate`() {
        val offered = offeredGrant(GrantorSigningEvidence(organizationActor, mandateId))
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, soleMandateId = UUID.randomUUID())

        assertThatThrownBy {
            runBlocking { service.accept(offered.id, grantee, UUID.randomUUID(), grantee) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `company offer cannot substitute a different issuer`() {
        val offered = offeredGrant(GrantorSigningEvidence(organizationActor, mandateId))
        val newRepresentative = UUID.randomUUID()
        coEvery { repository.findById(offered.id) } returns offered
        coEvery { authorityClient.authorityFor(grantor, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.DENIED)
        coEvery { authorityClient.authorityFor(grantor, newRepresentative) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, soleMandateId = UUID.randomUUID())

        assertThatThrownBy {
            runBlocking { service.accept(offered.id, grantee, UUID.randomUUID(), grantee) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 1) { authorityClient.authorityFor(grantor, organizationActor) }
        coVerify(exactly = 0) { authorityClient.authorityFor(grantor, newRepresentative) }
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    private fun offeredGrant(evidence: GrantorSigningEvidence?) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validFrom = now,
        validTo = now.plusDays(30),
        grantorSigningEvidence = evidence,
        createdAt = now,
        updatedAt = now,
    )

    private fun scaOk(partyId: UUID, purpose: String) {
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = partyId,
            purpose = purpose,
            status = "COMPLETED",
        )
    }

    private fun eligibilityOk(granteeKyc: String = "FULL", grantorName: String? = null, granteeName: String? = null) {
        coEvery { authorityClient.authorityFor(grantor, grantor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED, grantorName)
        coEvery { eligibilityClient.eligibilityOf(grantee) } returns
            PartyEligibility(grantee, true, granteeKyc, granteeName)
    }

    private fun offerCommand(
        capabilities: Set<DelegationCapability> = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
    ) = OfferDelegationCommand(
        callerPartyId = grantor,
        actorPartyId = grantor,
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        validTo = now.plusDays(30),
        grantScaSessionId = UUID.randomUUID(),
    )

    private fun previewCommand() = PreviewDelegationCommand(
        callerPartyId = grantor,
        actorPartyId = grantor,
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        validTo = now.plusDays(30),
    )
}
