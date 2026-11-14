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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0375 D5+D6 — which resource types a NEW offer may name.
 *
 * Its own class because `DelegationServiceTest` is already at detekt's `LargeClass` bound, and
 * because this is one rule: a type with no ownership oracle AND no delivery mechanism cannot be
 * offered, while DOCUMENT still can.
 */
class DelegationOfferableResourceTypeTest {

    private val repository: DelegationRepository = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val eligibilityClient: PartyEligibilityClient = mockk()
    private val authorityClient: GrantorAuthorityClient = mockk()
    private val ownershipClient: ResourceOwnershipClient = mockk()
    private val approvalGroupRepository: ApprovalGroupRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: DelegationService

    private val grantor: UUID = UUID.randomUUID()
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
    }

    /**
     * A decoupled challenge as the customer path actually presents it: PENDING, holding a
     * signature-verified device decision that only `consume` will promote.
     */
    /**
     * Issue #3604 — the accept screen showed the counterparty as a truncated UUID, so the person
     * asked to hand over authority over their money could not tell who was asking.
     *
     * Asserted on the SAVED AGGREGATE, not on the returned response: the point of the fix is that
     * the label is persisted at the moment of consent, so it survives a later rename of the party
     * and needs no runtime lookup (and therefore no new authority for customer-edge). A test that
     * only read the response would still pass against an implementation that resolved the name
     * on the way out.
     */
    /**
     * ADR-0375 D5+D6. PAYMENT and STATEMENT have no ownership oracle AND no delivery mechanism —
     * `ExternalDisclosureService.issue` accepts `DOCUMENT` only — so an offer naming them is
     * refused on the TYPE, before the ownership lookup that would also have refused it.
     *
     * The distinction is the point: an ownership error invites the grantor to retry with a
     * different resource id, and no id would ever work.
     */
    @Test
    fun `offer refuses a resource type that cannot be delivered, before the ownership lookup`() {
        for (type in listOf(DelegationResourceType.PAYMENT, DelegationResourceType.STATEMENT)) {
            assertThatThrownBy {
                runBlocking {
                    service.offer(
                        offerCommand().copy(
                            resourceType = type,
                            capabilities = setOf(DelegationCapability.OBJECT_READ),
                        ),
                    )
                }
            }
                .describedAs("%s must be refused on the type", type)
                .isInstanceOf(DelegationUnsupportedConstraintException::class.java)
                .extracting("code")
                .isEqualTo(DelegationUnsupportedConstraintException.CODE_RESOURCE_TYPE_NOT_OFFERABLE)
        }

        coVerify(exactly = 0) { ownershipClient.verifyOwnership(any(), any(), any()) }
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    /**
     * The control that keeps the one above from being a blanket refusal: DOCUMENT is still
     * offerable, and it is the type the sealed disclosure accepts.
     */
    @Test
    fun `a DOCUMENT object grant is still offerable`() {
        assertThatThrownBy {
            runBlocking {
                service.offer(
                    offerCommand().copy(
                        resourceType = DelegationResourceType.DOCUMENT,
                        capabilities = setOf(DelegationCapability.OBJECT_READ),
                    ),
                )
            }
        }
            .describedAs("DOCUMENT must NOT be refused on the type")
            .matches(
                {
                    it !is DelegationUnsupportedConstraintException ||
                        (it as DelegationUnsupportedConstraintException).code !=
                        DelegationUnsupportedConstraintException.CODE_RESOURCE_TYPE_NOT_OFFERABLE
                },
                "not a RESOURCE_TYPE_NOT_OFFERABLE refusal",
            )
    }

    /**
     * Historical grants of those types must stay READABLE — that is why the enum values and
     * `CAPABILITY_MATRIX` entries survive while `OFFERABLE_RESOURCE_TYPES` does not list them. A
     * narrowed matrix would have thrown here: the aggregate validates capabilities with
     * `CAPABILITY_MATRIX.getValue(resourceType)`, which raises for a missing key.
     */
    @Test
    fun `a historical grant of an unofferable type still constructs`() {
        val historical = offeredGrant().copy(
            resourceType = DelegationResourceType.STATEMENT,
            resourceId = UUID.randomUUID(),
            capabilities = setOf(DelegationCapability.OBJECT_READ),
        )

        assertThat(historical.resourceType).isEqualTo(DelegationResourceType.STATEMENT)
        assertThat(historical.hasCapability(DelegationCapability.OBJECT_READ)).isTrue()
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
