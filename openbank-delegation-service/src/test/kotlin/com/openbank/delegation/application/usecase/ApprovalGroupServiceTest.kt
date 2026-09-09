// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CreateApprovalGroupCommand
import com.openbank.delegation.application.port.`in`.ReviseApprovalGroupCommand
import com.openbank.delegation.application.port.out.ApprovalGroupRepository
import com.openbank.delegation.application.port.out.GrantorAuthority
import com.openbank.delegation.application.port.out.GrantorAuthorityClient
import com.openbank.delegation.application.port.out.GrantorAuthorityVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.event.ApprovalGroupChanged
import com.openbank.delegation.domain.model.ApprovalGroup
import com.openbank.libs.domain.event.DomainEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ApprovalGroupServiceTest {
    private val repository: ApprovalGroupRepository = mockk()
    private val eligibility: PartyEligibilityClient = mockk()
    private val sca: ScaChallengeClient = mockk()
    private val authority: GrantorAuthorityClient = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-09-09T08:00:00Z"), ZoneOffset.UTC)
    private val owner = UUID.randomUUID()
    private val organizationActor = UUID.randomUUID()
    private val memberA = UUID.randomUUID()
    private val memberB = UUID.randomUUID()
    private val scaId = UUID.randomUUID()
    private lateinit var service: ApprovalGroupService

    @BeforeEach
    fun setUp() {
        service = ApprovalGroupService(repository, eligibility, sca, authority, clock)
        coEvery { authority.authorityFor(owner, owner) } returns GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED)
        coEvery { repository.findByScaSessionId(any()) } returns null
        listOf(memberA, memberB).forEach { member ->
            coEvery { eligibility.eligibilityOf(member) } returns PartyEligibility(member, true, "FULL")
        }
        coEvery { sca.getChallenge(scaId) } returns
            ScaChallengeSnapshot(scaId, owner, "DELEGATION_APPROVAL_GROUP", "PENDING")
        coEvery { sca.consumeChallenge(scaId, owner, any()) } returns
            ScaChallengeSnapshot(scaId, owner, "DELEGATION_APPROVAL_GROUP", "COMPLETED")
    }

    @Test
    fun `exact create retry returns immutable original result without repeating side effects`(): Unit = runBlocking {
        val original = group()
        coEvery { repository.findByScaSessionId(scaId) } returns original

        val replayed = service.create(createCommand())

        assertThat(replayed).isEqualTo(original)
        coVerify(exactly = 0) { eligibility.eligibilityOf(any()) }
        coVerify(exactly = 0) { sca.getChallenge(any()) }
        coVerify(exactly = 0) { sca.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.create(any(), any()) }
    }

    @Test
    fun `SCA replay with altered authority is rejected without side effects`(): Unit = runBlocking {
        coEvery { repository.findByScaSessionId(scaId) } returns group()

        assertThatThrownBy {
            runBlocking { service.create(createCommand().copy(threshold = 1)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already used for a different command")

        coVerify(exactly = 0) { eligibility.eligibilityOf(any()) }
        coVerify(exactly = 0) { sca.consumeChallenge(any(), any(), any()) }
        coVerify(exactly = 0) { repository.create(any(), any()) }
    }

    @Test
    fun `concurrent identical create returns the committed winner`(): Unit = runBlocking {
        val winner = group()
        coEvery { repository.findByScaSessionId(scaId) } returnsMany listOf(null, winner)
        coEvery { repository.create(any(), any()) } throws RuntimeException(
            SQLException(
                "duplicate key violates constraint delegation_approval_group_commands_pkey (23505)",
                "23505",
            ),
        )

        assertThat(service.create(createCommand())).isEqualTo(winner)

        coVerify(exactly = 2) { repository.findByScaSessionId(scaId) }
        coVerify(exactly = 1) { sca.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `create validates members then consumes owner SCA and emits the complete revision`(): Unit = runBlocking {
        coEvery { repository.create(any(), any()) } answers { firstArg() }

        val created = service.create(createCommand())

        assertThat(created.members).containsExactlyInAnyOrder(memberA, memberB)
        assertThat(created.threshold).isEqualTo(2)
        assertThat(created.revision).isEqualTo(1)
        coVerify {
            sca.consumeChallenge(
                scaId,
                owner,
                ApprovalGroupScaBinding.create(owner, "Treasury", setOf(memberA, memberB), 2),
            )
        }
        coVerify {
            repository.create(
                created,
                match<DomainEvent> {
                    it is ApprovalGroupChanged && it.revision == 1L && it.members == setOf(memberA, memberB)
                },
            )
        }
    }

    @Test
    fun `another owner is refused before eligibility SCA or persistence`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.create(createCommand().copy(callerPartyId = UUID.randomUUID())) }
        }.isInstanceOf(ApprovalGroupForbiddenException::class.java)

        coVerify(exactly = 0) { eligibility.eligibilityOf(any()) }
        coVerify(exactly = 0) { sca.getChallenge(any()) }
        coVerify(exactly = 0) { repository.create(any(), any()) }
    }

    @Test
    fun `organization actor authority is revalidated and the human actor consumes SCA`(): Unit = runBlocking {
        coEvery { authority.authorityFor(owner, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.AUTHORIZED)
        coEvery { sca.getChallenge(scaId) } returns
            ScaChallengeSnapshot(scaId, organizationActor, "DELEGATION_APPROVAL_GROUP", "PENDING")
        coEvery { sca.consumeChallenge(scaId, organizationActor, any()) } returns
            ScaChallengeSnapshot(scaId, organizationActor, "DELEGATION_APPROVAL_GROUP", "COMPLETED")
        coEvery { repository.create(any(), any()) } answers { firstArg() }

        val created = service.create(createCommand().copy(actorPartyId = organizationActor))

        assertThat(created.ownerPartyId).isEqualTo(owner)
        coVerify { authority.authorityFor(owner, organizationActor) }
        coVerify { sca.consumeChallenge(scaId, organizationActor, any()) }
    }

    @Test
    fun `revoked organization actor is refused before replay lookup or SCA`(): Unit = runBlocking {
        coEvery { authority.authorityFor(owner, organizationActor) } returns
            GrantorAuthority(GrantorAuthorityVerdict.DENIED)

        assertThatThrownBy {
            runBlocking { service.create(createCommand().copy(actorPartyId = organizationActor)) }
        }.isInstanceOf(DelegationGrantorAuthorityException::class.java)

        coVerify(exactly = 0) { repository.findByScaSessionId(any()) }
        coVerify(exactly = 0) { sca.getChallenge(any()) }
        coVerify(exactly = 0) { sca.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `ineligible member refuses the change without burning SCA`(): Unit = runBlocking {
        coEvery { eligibility.eligibilityOf(memberB) } returns PartyEligibility(memberB, false, "NONE")

        assertThatThrownBy { runBlocking { service.create(createCommand()) } }
            .isInstanceOf(ApprovalGroupMemberIneligibleException::class.java)

        coVerify(exactly = 0) { sca.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `invalid threshold is rejected before member lookup or SCA consumption`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.create(createCommand().copy(threshold = 3)) }
        }.isInstanceOf(IllegalArgumentException::class.java)

        coVerify(exactly = 0) { eligibility.eligibilityOf(any()) }
        coVerify(exactly = 0) { sca.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `revision replaces the roster and increments immutable version identity`(): Unit = runBlocking {
        val current = group()
        coEvery { repository.findById(current.id) } returns current
        coEvery { repository.update(any(), any(), any()) } answers { firstArg() }

        val revised = service.revise(
            ReviseApprovalGroupCommand(
                id = current.id,
                ownerPartyId = owner,
                callerPartyId = owner,
                actorPartyId = owner,
                expectedRevision = 1,
                name = "Treasury board",
                members = setOf(memberA),
                threshold = 1,
                scaSessionId = scaId,
            ),
        )

        assertThat(revised.revision).isEqualTo(2)
        assertThat(revised.members).containsExactly(memberA)
        coVerify { repository.update(revised, 1, any()) }
    }

    @Test
    fun `deactivation is immediate and cannot rewrite a snapshot already taken`(): Unit = runBlocking {
        val current = group()
        coEvery { repository.findById(current.id) } returns current
        coEvery { repository.update(any(), any(), any()) } answers { firstArg() }

        val deactivated = service.deactivate(current.id, owner, owner, owner)

        assertThat(deactivated.active).isFalse()
        assertThat(deactivated.revision).isEqualTo(2)
        coVerify(exactly = 0) { sca.consumeChallenge(any(), any(), any()) }
    }

    @Test
    fun `SCA binding is deterministic but changes with authority-relevant content`() {
        val first = ApprovalGroupScaBinding.create(owner, " Treasury ", setOf(memberB, memberA), 2)
        val reordered = ApprovalGroupScaBinding.create(owner, "Treasury", setOf(memberA, memberB), 2)
        val weaker = ApprovalGroupScaBinding.create(owner, "Treasury", setOf(memberA, memberB), 1)

        assertThat(first).isEqualTo(reordered)
        assertThat(first).matches("approval-group:v1:[0-9a-f]{64}")
        assertThat(weaker).isNotEqualTo(first)
    }

    private fun createCommand() = CreateApprovalGroupCommand(
        ownerPartyId = owner,
        callerPartyId = owner,
        actorPartyId = owner,
        name = "  Treasury  ",
        members = setOf(memberA, memberB),
        threshold = 2,
        scaSessionId = scaId,
    )

    private fun group() = ApprovalGroup(
        ownerPartyId = owner,
        name = "Treasury",
        members = setOf(memberA, memberB),
        threshold = 2,
        lastScaSessionId = scaId,
        createdAt = OffsetDateTime.now(clock),
        updatedAt = OffsetDateTime.now(clock),
    )
}
