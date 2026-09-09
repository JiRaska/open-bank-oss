// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.ApprovalGroupUseCase
import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.`in`.CreateApprovalGroupCommand
import com.openbank.delegation.application.port.`in`.ReviseApprovalGroupCommand
import com.openbank.delegation.application.port.out.ApprovalGroupRepository
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.domain.event.ApprovalGroupChanged
import com.openbank.delegation.domain.model.ApprovalGroup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class ApprovalGroupForbiddenException(message: String) : RuntimeException(message)
class ApprovalGroupMemberIneligibleException(memberId: UUID) :
    RuntimeException("approval group member $memberId is not an active, identified party")
class ApprovalGroupScaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@ApplicationScoped
class ApprovalGroupService(
    private val repository: ApprovalGroupRepository,
    private val eligibilityClient: PartyEligibilityClient,
    private val scaClient: ScaChallengeClient,
    private val clock: Clock,
) : ApprovalGroupUseCase {

    @Inject
    constructor(
        repository: ApprovalGroupRepository,
        eligibilityClient: PartyEligibilityClient,
        scaClient: ScaChallengeClient,
    ) : this(repository, eligibilityClient, scaClient, Clock.systemUTC())

    override suspend fun create(command: CreateApprovalGroupCommand): ApprovalGroup {
        requireOwner(command.callerPartyId, command.ownerPartyId)
        val now = OffsetDateTime.now(clock)
        val group = ApprovalGroup(
            ownerPartyId = command.ownerPartyId,
            name = command.name.trim(),
            members = command.members,
            threshold = command.threshold,
            lastScaSessionId = command.scaSessionId,
            createdAt = now,
            updatedAt = now,
        )
        validateMembers(group.members)
        consumeManagementSca(
            command.scaSessionId,
            command.ownerPartyId,
            ApprovalGroupScaBinding.create(command.ownerPartyId, group.name, group.members, group.threshold),
        )
        return repository.create(
            group,
            ApprovalGroupChanged.from(group, "ApprovalGroupCreated", clock.instant()),
        )
    }

    override suspend fun revise(command: ReviseApprovalGroupCommand): ApprovalGroup {
        requireOwner(command.callerPartyId, command.ownerPartyId)
        val current = owned(command.id, command.ownerPartyId)
        if (current.revision != command.expectedRevision) {
            throw com.openbank.delegation.application.port.out.ApprovalGroupConcurrentUpdateException(
                command.id,
                command.expectedRevision,
            )
        }
        val revised = current.revise(
            command.name,
            command.members,
            command.threshold,
            command.scaSessionId,
            OffsetDateTime.now(clock),
        )
        validateMembers(revised.members)
        consumeManagementSca(
            command.scaSessionId,
            command.ownerPartyId,
            ApprovalGroupScaBinding.revise(
                command.ownerPartyId,
                command.id,
                command.expectedRevision,
                revised.name,
                revised.members,
                revised.threshold,
            ),
        )
        return repository.update(
            revised,
            current.revision,
            ApprovalGroupChanged.from(revised, "ApprovalGroupRevised", clock.instant()),
        )
    }

    override suspend fun deactivate(id: UUID, ownerPartyId: UUID, callerPartyId: CallerPartyId): ApprovalGroup {
        requireOwner(callerPartyId, ownerPartyId)
        val current = owned(id, ownerPartyId)
        val deactivated = current.deactivate(OffsetDateTime.now(clock))
        return repository.update(
            deactivated,
            current.revision,
            ApprovalGroupChanged.from(deactivated, "ApprovalGroupDeactivated", clock.instant()),
        )
    }

    override suspend fun get(id: UUID, ownerPartyId: UUID, callerPartyId: CallerPartyId): ApprovalGroup {
        requireOwner(callerPartyId, ownerPartyId)
        return owned(id, ownerPartyId)
    }

    override suspend fun list(ownerPartyId: UUID, callerPartyId: CallerPartyId): List<ApprovalGroup> {
        requireOwner(callerPartyId, ownerPartyId)
        return repository.findByOwner(ownerPartyId)
    }

    private suspend fun validateMembers(members: Set<UUID>) {
        members.forEach { memberId ->
            val member = eligibilityClient.eligibilityOf(memberId)
            if (!member.active || member.kycLevel == "NONE") throw ApprovalGroupMemberIneligibleException(memberId)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun consumeManagementSca(sessionId: UUID, ownerPartyId: UUID, reference: String) {
        val challenge = try {
            scaClient.getChallenge(sessionId)
        } catch (e: Exception) {
            throw ApprovalGroupScaException("approval-group SCA $sessionId could not be verified", e)
        }
        if (challenge.partyId != ownerPartyId || challenge.purpose != SCA_PURPOSE) {
            throw ApprovalGroupScaException("approval-group SCA $sessionId does not match owner or purpose")
        }
        try {
            scaClient.consumeChallenge(sessionId, ownerPartyId, reference)
        } catch (e: Exception) {
            throw ApprovalGroupScaException("approval-group SCA $sessionId could not be consumed", e)
        }
    }

    private suspend fun owned(id: UUID, ownerPartyId: UUID): ApprovalGroup {
        val group = repository.findById(id) ?: throw NotFoundException("approval group $id not found")
        if (group.ownerPartyId != ownerPartyId) throw NotFoundException("approval group $id not found")
        return group
    }

    private fun requireOwner(callerPartyId: CallerPartyId, ownerPartyId: UUID) {
        if (callerPartyId != ownerPartyId) {
            throw ApprovalGroupForbiddenException("caller may manage only their own approval groups")
        }
    }

    private companion object {
        const val SCA_PURPOSE = "DELEGATION_APPROVAL_GROUP"
    }
}

/** Stable, language-neutral SCA fingerprint. Length prefixes remove delimiter ambiguity. */
object ApprovalGroupScaBinding {
    fun create(owner: UUID, name: String, members: Set<UUID>, threshold: Int): String =
        digest("CREATE", owner, null, null, name, members, threshold)

    fun revise(
        owner: UUID,
        id: UUID,
        expectedRevision: Long,
        name: String,
        members: Set<UUID>,
        threshold: Int,
    ): String = digest("REVISE", owner, id, expectedRevision, name, members, threshold)

    private fun digest(
        operation: String,
        owner: UUID,
        id: UUID?,
        expectedRevision: Long?,
        name: String,
        members: Set<UUID>,
        threshold: Int,
    ): String {
        val fields = listOf(
            "approval-group-v1",
            operation,
            owner.toString(),
            id?.toString().orEmpty(),
            expectedRevision?.toString().orEmpty(),
            name.trim(),
            members.map(UUID::toString).sorted().joinToString(","),
            threshold.toString(),
        )
        val canonical = fields.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "approval-group:v1:$hex"
    }
}
