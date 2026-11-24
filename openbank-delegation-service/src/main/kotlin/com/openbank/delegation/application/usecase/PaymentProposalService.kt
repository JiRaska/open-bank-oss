// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.ResolveMcpClientGrantUseCase
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PaymentProposalRepository
import com.openbank.delegation.application.port.out.ProposalCreateOutcome
import com.openbank.delegation.application.port.out.ProposalLimits
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalLimitException
import com.openbank.delegation.domain.model.PaymentProposalNotFoundException
import com.openbank.delegation.domain.model.PaymentProposalNotPermittedException
import com.openbank.delegation.domain.model.PaymentProposalPayload
import com.openbank.delegation.domain.model.PaymentProposalStateException
import com.openbank.delegation.domain.model.PaymentProposalStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

data class CreatePaymentProposalCommand(
    val partyId: UUID,
    /**
     * The connected client's `azp`, as mcp-service read it from the customer token IT verified.
     * TRUSTED here, not re-verified: mcp-service calls with its own service-account token and
     * passes this in the body (threat model, "Spoofing a grant"). Never from the model.
     */
    val mcpClientId: String,
    /** The DPoP key thumbprint (`cnf.jkt`) of that token; trusted from mcp-service likewise. */
    val keyThumbprint: String,
    val payload: PaymentProposalPayload,
    val connectionId: UUID? = null,
)

data class CreatedPaymentProposal(val proposal: PaymentProposal, val deduplicated: Boolean)

/**
 * ADR-0383 D6, the proposal store. The ONLY writer of `delegation_payment_proposals`.
 *
 * Who may call what is decided twice, independently: OPA (`delegation_rest_ext.rego`, the
 * `delegation.proposal.*` family) and the resource, which checks the caller's verified client id
 * before reaching this class. This class adds the third half that neither can: the proposal is
 * created only under an ACTIVE MCP_CLIENT grant found server-side from (party, client, key), whose
 * capabilities include propose-payment and whose resource is the debtor account.
 *
 * Nothing in this class moves money or can be made to. "Approve" records that the customer's own
 * SCA-consuming session approved it; the payment itself is initiated by customer-edge through the
 * ordinary payment path, after that SCA.
 */
@ApplicationScoped
class PaymentProposalService(
    private val proposals: PaymentProposalRepository,
    private val grants: DelegationRepository,
    private val mcpGrants: ResolveMcpClientGrantUseCase,
    private val ownership: ResourceOwnershipClient,
    private val clock: Clock,
    private val ttl: Duration,
    private val limits: ProposalLimits,
) {
    @Inject
    constructor(
        proposals: PaymentProposalRepository,
        grants: DelegationRepository,
        mcpGrants: ResolveMcpClientGrantUseCase,
        ownership: ResourceOwnershipClient,
        @ConfigProperty(name = "openbank.delegation.payment-proposals.ttl", defaultValue = "PT24H") ttl: Duration,
        @ConfigProperty(name = "openbank.delegation.payment-proposals.max-open-per-party", defaultValue = "10")
        maxOpen: Int,
        @ConfigProperty(
            name = "openbank.delegation.payment-proposals.max-created-per-party-per-day",
            defaultValue = "20",
        )
        maxPerDay: Int,
    ) : this(proposals, grants, mcpGrants, ownership, Clock.systemUTC(), ttl, ProposalLimits(maxOpen, maxPerDay))

    init {
        require(!ttl.isNegative && !ttl.isZero && ttl <= MAX_TTL) { "proposal ttl must be within (0, $MAX_TTL]" }
    }

    suspend fun create(command: CreatePaymentProposalCommand): CreatedPaymentProposal {
        val now = OffsetDateTime.now(clock)
        val grant = proposeGrantFor(command, now)
        val id = Ids.newId()
        val candidate = PaymentProposal(
            id = id,
            partyId = command.partyId,
            grantId = grant.id,
            mcpClientId = command.mcpClientId,
            payload = command.payload,
            payloadSha256 = PaymentProposal.canonicalSha256(id, command.partyId, command.payload),
            status = PaymentProposalStatus.PENDING,
            createdAt = now,
            expiresAt = now.plus(ttl),
        )
        return when (val outcome = proposals.create(candidate, limits, now)) {
            is ProposalCreateOutcome.Created -> CreatedPaymentProposal(outcome.proposal, deduplicated = false)
            is ProposalCreateOutcome.Deduplicated -> CreatedPaymentProposal(outcome.proposal, deduplicated = true)
            is ProposalCreateOutcome.LimitReached -> throw PaymentProposalLimitException(outcome.reason)
        }
    }

    /** A party sees only its own proposals; another party's id is indistinguishable from none. */
    suspend fun get(id: UUID, partyId: UUID): PaymentProposal = proposals.findById(id)
        ?.takeIf { it.partyId == partyId }
        ?.let { reconcileGrant(it, OffsetDateTime.now(clock)) }
        ?: throw PaymentProposalNotFoundException()

    suspend fun listPending(partyId: UUID): List<PaymentProposal> {
        val now = OffsetDateTime.now(clock)
        return proposals.findByParty(partyId, LIST_LIMIT)
            .filter { it.isOpen(now) }
            .map { reconcileGrant(it, now) }
            .filter { it.isOpen(now) }
    }

    /**
     * A proposal is only as alive as the grant it was made under. If that grant is no longer
     * ACTIVE (revoked, suspended, expired, renounced) a PENDING proposal is REJECTED — persisted,
     * with its event — before anyone can read it as approvable. The revoke/suspend path also
     * rejects pending proposals in its own transaction ([PaymentProposalRepository.rejectPendingForGrant]);
     * this is the read-side half for grants that lapsed without a write (validTo passing).
     */
    private suspend fun reconcileGrant(proposal: PaymentProposal, now: OffsetDateTime): PaymentProposal {
        if (proposal.effectiveStatus(now) != PaymentProposalStatus.PENDING) return proposal
        val live = grants.findById(proposal.grantId)?.isActiveOn(now) == true
        if (live) return proposal
        return proposals.transition(proposal.id) { current ->
            if (current.effectiveStatus(now) == PaymentProposalStatus.PENDING) current.reject(now) else current
        } ?: proposal
    }

    suspend fun approve(id: UUID, partyId: UUID, payloadSha256: String): PaymentProposal {
        val current = get(id, partyId)
        val now = OffsetDateTime.now(clock)
        // APPROVED-but-unexecuted is the retry of a payment that failed after approval: no write.
        if (current.status == PaymentProposalStatus.APPROVED) {
            // A retry is only as alive as the grant: a revoked or suspended grant ends it.
            if (grants.findById(current.grantId)?.isActiveOn(now) != true) {
                throw PaymentProposalStateException("proposal $id can no longer be retried: its grant is not active")
            }
            return current.confirmRetry(payloadSha256, now)
        }
        return mutate(id) { it.approve(payloadSha256, now) }
    }

    suspend fun reject(id: UUID, partyId: UUID): PaymentProposal {
        get(id, partyId)
        val now = OffsetDateTime.now(clock)
        return mutate(id) { it.reject(now) }
    }

    suspend fun recordExecution(id: UUID, partyId: UUID, payloadSha256: String, paymentId: UUID?): PaymentProposal {
        get(id, partyId)
        val now = OffsetDateTime.now(clock)
        return mutate(id) {
            if (paymentId != null) {
                it.recordExecuted(payloadSha256, paymentId, now)
            } else {
                it.recordExecutionFailed(payloadSha256, now)
            }
        }
    }

    private suspend fun mutate(id: UUID, transition: (PaymentProposal) -> PaymentProposal): PaymentProposal =
        proposals.transition(id, transition) ?: throw PaymentProposalNotFoundException()

    /** A cached V2 receipt still requires live consent for this exact connection and account. */
    suspend fun requireLiveCreateGrant(command: CreatePaymentProposalCommand) {
        proposeGrantFor(command, OffsetDateTime.now(clock))
    }

    private suspend fun proposeGrantFor(command: CreatePaymentProposalCommand, now: OffsetDateTime): DelegationGrant {
        val connection = command.connectionId
        val candidates = if (connection == null) {
            grants.findByGrantorId(command.partyId)
        } else {
            mcpGrants.resolveForConnection(command.partyId, command.mcpClientId, command.keyThumbprint, connection)
        }
        val eligible = candidates.filter { grant ->
            grant.granteeType == DelegationGranteeType.MCP_CLIENT &&
                grant.grantorPartyId == command.partyId &&
                grant.granteeClientId == command.mcpClientId &&
                grant.granteeKeyThumbprint == command.keyThumbprint &&
                grant.mcpConnectionId == connection &&
                grant.isActiveOn(now) &&
                grant.hasCapability(DelegationCapability.ACCOUNT_PROPOSE_PAYMENT) &&
                grant.resourceType == DelegationResourceType.ACCOUNT &&
                grant.resourceId == command.payload.debtorAccountId
        }
        val grant = (if (connection == null) eligible.firstOrNull() else eligible.singleOrNull())
            ?: throw PaymentProposalNotPermittedException(NO_PROPOSE_GRANT)
        if (connection != null &&
            ownership.verifyOwnership(command.partyId, grant.resourceType, grant.resourceId) != OwnershipVerdict.OWNED
        ) {
            throw PaymentProposalNotPermittedException(NO_PROPOSE_GRANT)
        }
        return grant
    }

    private companion object {
        const val LIST_LIMIT = 50
        const val NO_PROPOSE_GRANT = "no active MCP_CLIENT grant permits proposing a payment from this account"
        val MAX_TTL: Duration = Duration.ofHours(24)
    }
}
