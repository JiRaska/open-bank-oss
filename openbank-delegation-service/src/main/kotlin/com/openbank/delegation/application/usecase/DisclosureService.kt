// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.GetDisclosureUseCase
import com.openbank.delegation.application.port.`in`.PrepareDisclosureCommand
import com.openbank.delegation.application.port.`in`.PrepareDisclosureUseCase
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.DisclosureRepository
import com.openbank.delegation.domain.event.DisclosureSnapshotRequested
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.Disclosure
import com.openbank.delegation.domain.model.DisclosureStatus
import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

class DisclosureNotFoundException(id: UUID) : RuntimeException("Disclosure not found: $id")
class DisclosureForbiddenException : RuntimeException("Disclosure is not available to the authenticated party")
class DisclosureNotEligibleException(message: String) : RuntimeException(message)
class DisclosureIdempotencyConflictException : RuntimeException("requestId is already bound to another disclosure")

@ApplicationScoped
class DisclosureService(
    private val grants: DelegationRepository,
    private val disclosures: DisclosureRepository,
    private val clock: Clock,
) : PrepareDisclosureUseCase,
    GetDisclosureUseCase {
    override suspend fun prepare(command: PrepareDisclosureCommand): Disclosure {
        disclosures.findByRequestId(command.requestId)?.let { existing ->
            if (existing.delegationId != command.delegationId || existing.grantorPartyId != command.callerPartyId) {
                throw DisclosureIdempotencyConflictException()
            }
            return existing
        }
        val grant = grants.findById(command.delegationId) ?: throw DelegationNotFoundException(command.delegationId)
        if (command.callerPartyId == null ||
            command.callerPartyId != grant.grantorPartyId
        ) {
            throw DisclosureForbiddenException()
        }
        if (grant.status != DelegationStatus.ACTIVE) throw DisclosureNotEligibleException("delegation must be ACTIVE")
        if (grant.resourceType != DelegationResourceType.DOCUMENT) {
            throw DisclosureNotEligibleException("only DOCUMENT delegations can prepare a PDF disclosure")
        }
        val now = Instant.now(clock)
        val candidate = Disclosure(
            id = UUID.nameUUIDFromBytes("disclosure:${command.requestId}".toByteArray(StandardCharsets.UTF_8)),
            requestId = command.requestId,
            delegationId = grant.id,
            grantorPartyId = grant.grantorPartyId,
            sourceDocumentId = grant.resourceId,
            status = DisclosureStatus.REQUESTED,
            createdAt = now,
            updatedAt = now,
        )
        val event =
            DisclosureSnapshotRequested(
                candidate.id,
                candidate.requestId,
                candidate.sourceDocumentId,
                grant.grantorPartyId.toString(),
                now,
            )
        disclosures.create(candidate, event)?.let { return it }
        disclosures.findByRequestId(command.requestId)?.let { winner ->
            if (winner.delegationId == command.delegationId && winner.grantorPartyId == command.callerPartyId) {
                return winner
            }
            throw DisclosureIdempotencyConflictException()
        }
        throw DisclosureNotEligibleException("delegation is no longer active or eligible")
    }

    override suspend fun get(id: UUID, callerPartyId: UUID?): Disclosure {
        val disclosure = disclosures.findById(id) ?: throw DisclosureNotFoundException(id)
        if (callerPartyId == null || callerPartyId != disclosure.grantorPartyId) throw DisclosureForbiddenException()
        return disclosure
    }
}
