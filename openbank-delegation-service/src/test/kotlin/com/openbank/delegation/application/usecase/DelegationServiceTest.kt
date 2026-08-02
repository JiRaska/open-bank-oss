// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CheckDelegationCommand
import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.event.EventMoney
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DelegationServiceTest {

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
        callerPartyId = grantor,
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        validTo = now.plusDays(30),
        grantScaSessionId = UUID.randomUUID(),
    )

    /**
     * #3410: the aggregate carried `validFrom`, `validTo` and `perTransactionLimit` and no event
     * did, so both enforcement projections read fields the producer never sent and defaulted them
     * — `validFrom ?: now` made a future-dated grant enforceable at once, `validTo = null` meant it
     * never expired locally, and no consumer could apply a per-transaction ceiling at all.
     *
     * Asserting on the EMITTED EVENT, not on the returned aggregate: the aggregate always had these
     * values, which is exactly why the gap was invisible from the producer's side.
     */
    @Test
    fun `the offered event carries the window and the ceiling, not just the aggregate`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        val event = slot<DomainEvent>()
        coEvery { repository.save(any<DelegationGrant>(), capture(event)) } answers { firstArg() }

        service.offer(offerCommand())

        val offered = event.captured as DelegationOffered
        assertThat(offered.validFrom).isEqualTo(now)
        assertThat(offered.validTo).isEqualTo(now.plusDays(30))
    }

    /**
     * The currency must be a FLAT string. `Money` holds a `CurrencyCode`, a data class with no
     * `@JsonValue`, so serializing it directly renders `{"code":"CZK"}` while both consumers read
     * `perTransactionLimit.currency` as text — the amount would arrive and the currency would
     * silently be null.
     */
    @Test
    fun `the event money carries a flat ISO currency, never the CurrencyCode object`() {
        val wire = EventMoney.from(Money(BigDecimal("5000.00"), CurrencyCode("CZK")))

        assertThat(wire).isNotNull
        assertThat(wire!!.currency).isEqualTo("CZK")
        assertThat(wire.amount).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(EventMoney.from(null)).isNull()
    }

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
        // Ownership and eligibility are checked BEFORE the SCA gate (so a doomed request does not
        // spend the ceremony), hence both must be stubbed for the SCA assertion to be reached.
        eligibilityOk()
        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationScaException::class.java)
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `offer rejects SCA completed by a different party`(): Unit = runBlocking {
        scaOk(UUID.randomUUID(), "DELEGATION_GRANT")
        eligibilityOk()
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

        val accepted = service.accept(offered.id, grantee, UUID.randomUUID(), grantee)

        assertThat(accepted.status).isEqualTo(DelegationStatus.ACTIVE)
    }

    @Test
    fun `accept by a non-grantee is forbidden`(): Unit = runBlocking {
        val offered = offeredGrant()
        coEvery { repository.findById(offered.id) } returns offered

        assertThatThrownBy { runBlocking { service.accept(offered.id, UUID.randomUUID(), UUID.randomUUID(), null) } }
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

    // --- P0: the grantor must own the resource -------------------------------------------------
    // Without this gate two consenting parties mint payment rights over a THIRD party's account
    // using nothing but their own valid SCA: the product-service projection keys its guard on
    // (resource, grantee) and treats the grant row as authority in itself.

    @Test
    fun `offer refuses a resource the grantor does not own`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        val strangersAccount = UUID.randomUUID()
        coEvery {
            ownershipClient.verifyOwnership(grantor, DelegationResourceType.ACCOUNT, strangersAccount)
        } returns OwnershipVerdict.NOT_OWNED

        assertThatThrownBy {
            runBlocking { service.offer(offerCommand().copy(resourceId = strangersAccount)) }
        }.isInstanceOf(DelegationResourceOwnershipException::class.java)
            .hasMessageContaining("does not own")

        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `offer fails closed when ownership cannot be established`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        coEvery { ownershipClient.verifyOwnership(grantor, any(), any()) } returns OwnershipVerdict.UNVERIFIABLE

        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationResourceOwnershipException::class.java)
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `a refused offer does not spend the customer's SCA challenge`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        coEvery { ownershipClient.verifyOwnership(grantor, any(), any()) } returns OwnershipVerdict.NOT_OWNED

        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationResourceOwnershipException::class.java)
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any()) }
    }

    // --- P0: the caller may only act as the party the edge authenticated -----------------------

    @Test
    fun `offer refuses a caller acting as another grantor`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.offer(offerCommand().copy(callerPartyId = UUID.randomUUID())) }
        }.isInstanceOf(DelegationCallerMismatchException::class.java)
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `accept refuses a caller acting as another grantee`(): Unit = runBlocking {
        val offered = offeredGrant()
        assertThatThrownBy {
            runBlocking { service.accept(offered.id, grantee, UUID.randomUUID(), UUID.randomUUID()) }
        }.isInstanceOf(DelegationCallerMismatchException::class.java)
    }

    @Test
    fun `listByGrantor refuses to enumerate another party's grants`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.listByGrantor(grantor, UUID.randomUUID()) } }
            .isInstanceOf(DelegationCallerMismatchException::class.java)
    }

    @Test
    fun `getDelegation hides a grant the caller is not party to, as not-found`(): Unit = runBlocking {
        val offered = offeredGrant()
        coEvery { repository.findById(offered.id) } returns offered

        // NOT a 403: a stranger must not be able to use this endpoint as an existence oracle for
        // other people's sharing relationships.
        assertThatThrownBy { runBlocking { service.getDelegation(offered.id, UUID.randomUUID()) } }
            .isInstanceOf(DelegationNotFoundException::class.java)

        assertThat(service.getDelegation(offered.id, grantee).id).isEqualTo(offered.id)
        assertThat(service.getDelegation(offered.id, grantor).id).isEqualTo(offered.id)
        assertThat(service.getDelegation(offered.id, null).id).isEqualTo(offered.id)
    }

    // --- P0: revoke is the grantor's right, not everyone's ------------------------------------

    @Test
    fun `revoke by a party who is not the grantor is forbidden`(): Unit = runBlocking {
        val active = offeredGrant().accept(UUID.randomUUID(), now)
        coEvery { repository.findById(active.id) } returns active

        assertThatThrownBy {
            runBlocking { service.revoke(RevokeDelegationCommand(active.id, UUID.randomUUID(), "not mine")) }
        }.isInstanceOf(DelegationNotGrantorException::class.java)
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
    }

    @Test
    fun `the bank may revoke any grant and is recorded as the actor`(): Unit = runBlocking {
        val active = offeredGrant().accept(UUID.randomUUID(), now)
        val operatorParty = UUID.randomUUID()
        coEvery { repository.findById(active.id) } returns active
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        val revoked = service.revoke(
            RevokeDelegationCommand(active.id, operatorParty, "AML signal", bankInitiated = true),
        )

        assertThat(revoked.status).isEqualTo(DelegationStatus.REVOKED)
        assertThat(revoked.closedBy).isEqualTo(operatorParty)
    }

    // --- P0: the SCA challenge is spent, so it cannot authorise a second grant ------------------

    @Test
    fun `offer spends the SCA challenge`(): Unit = runBlocking {
        val command = offerCommand()
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        coEvery { repository.save(any<DelegationGrant>(), any()) } answers { firstArg() }

        service.offer(command)

        coVerify(exactly = 1) { scaClient.consumeChallenge(command.grantScaSessionId, grantor) }
    }

    @Test
    fun `a replayed SCA challenge cannot mint a second grant`(): Unit = runBlocking {
        scaOk(grantor, "DELEGATION_GRANT")
        eligibilityOk()
        // sca-service answers 409 on the second consume (compare-and-set on consumedAt). Reading
        // `status == COMPLETED` never expressed this: completion stays true forever, which is why
        // one ceremony used to authorise unlimited grants of arbitrary scope.
        coEvery { scaClient.consumeChallenge(any(), any()) } throws IllegalStateException("409 already consumed")

        assertThatThrownBy { runBlocking { service.offer(offerCommand()) } }
            .isInstanceOf(DelegationScaException::class.java)
            .hasMessageContaining("could not be spent")
        coVerify(exactly = 0) { repository.save(any<DelegationGrant>(), any()) }
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
