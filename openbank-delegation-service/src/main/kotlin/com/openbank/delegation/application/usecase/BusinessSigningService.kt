// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.AppliedChange
import com.openbank.delegation.application.port.out.ApprovalLink
import com.openbank.delegation.application.port.out.ApprovalScaVerifier
import com.openbank.delegation.application.port.out.BusinessSigningRepository
import com.openbank.delegation.application.port.out.MandateDirectory
import com.openbank.delegation.application.port.out.MandateDirectoryUnavailableException
import com.openbank.delegation.application.port.out.ScaVerdict
import com.openbank.delegation.application.port.out.Transition
import com.openbank.delegation.application.port.out.TransitionContext
import com.openbank.delegation.domain.event.ApprovalCompleted
import com.openbank.delegation.domain.event.ApprovalExpired
import com.openbank.delegation.domain.event.ApprovalRejected
import com.openbank.delegation.domain.event.ApprovalRequested
import com.openbank.delegation.domain.event.ApprovalSigned
import com.openbank.delegation.domain.event.PaymentReleaseFailed
import com.openbank.delegation.domain.event.PaymentReleased
import com.openbank.delegation.domain.model.ApprovalKind
import com.openbank.delegation.domain.model.ApprovalRejection
import com.openbank.delegation.domain.model.ApprovalRequest
import com.openbank.delegation.domain.model.ApprovalSignature
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.delegation.domain.model.Iban
import com.openbank.delegation.domain.model.PaymentFacts
import com.openbank.delegation.domain.model.SignatureRefusal
import com.openbank.delegation.domain.model.SignatureRefusedException
import com.openbank.delegation.domain.model.SignerGroup
import com.openbank.delegation.domain.model.SigningAmount
import com.openbank.delegation.domain.model.SigningEvaluation
import com.openbank.delegation.domain.model.SigningPolicy
import com.openbank.delegation.domain.model.SigningPolicyEvaluator
import com.openbank.delegation.domain.model.SigningPolicyRule
import com.openbank.delegation.domain.model.TrustedPayee
import com.openbank.delegation.domain.model.TrustedPayeeStatus
import com.openbank.libs.domain.event.DomainEvent
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Every refusal on the signing boundary: an HTTP status, a stable machine code, a message. */
class BusinessSigningException(val status: Int, val code: String, message: String) : RuntimeException(message)

/** Serialises frozen payloads in ONE canonical form, so the SHA-256 a signer links to is reproducible. */
interface SigningPayloadCodec {
    fun canonical(value: Any?): String

    fun parseObject(json: String): Map<String, Any?>
}

data class PaymentApprovalCommand(
    val entityPartyId: UUID,
    val initiatorPartyId: UUID,
    val initiatorScaChallengeId: UUID,
    val amount: SigningAmount,
    val creditorIban: String,
    val creditorName: String?,
    val rail: String,
    val payload: Map<String, Any?>,
    val ttl: Duration?,
)

data class ReleaseClaim(val claimToken: UUID, val payload: String)

data class PendingForHuman(val entities: List<PendingEntity>, val items: List<ApprovalRequest>)

data class PendingEntity(val entityPartyId: UUID, val entityName: String?, val count: Int, val oldestExpiresAt: Instant)

/**
 * ADR-0312 use cases. Every guard named in the spec's Rules section lives on one code path here
 * or in [ApprovalRequest] and is proven by a sabotage test:
 *  distinct signer / initiator-not-cosigner  → [ApprovalRequest.sign] ALREADY_SIGNED
 *  live mandate re-check                     → [requireLiveMandate] at sign, [claimRelease] at release
 *  dynamic-linking hash                      → [sign] consumes the challenge with [ApprovalLink]
 *  single-use release                        → [BusinessSigningRepository.claimRelease] CAS
 *  expiry                                    → [ApprovalRequest.sign] EXPIRED, [claimRelease], [expireDue]
 *  trusted-payee-only-after-approval         → payees are written only by [AppliedChange.AddPayee]
 *  policy-change-needs-full-round            → [createAdministrative] uses the strictest rule
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class BusinessSigningService(
    private val repository: BusinessSigningRepository,
    private val mandates: MandateDirectory,
    private val sca: ApprovalScaVerifier,
    private val codec: SigningPayloadCodec,
    private val clock: Clock,
) {

    // ---------------------------------------------------------------- policy, groups, payees

    suspend fun effectivePolicy(entityPartyId: UUID): SigningPolicy =
        repository.findPolicy(entityPartyId) ?: SigningPolicy.derived(entityPartyId, liveMandates(entityPartyId))

    suspend fun groups(entityPartyId: UUID): List<SignerGroup> = repository.listGroups(entityPartyId)

    suspend fun trustedPayees(entityPartyId: UUID): List<TrustedPayee> = repository.listActivePayees(entityPartyId)

    suspend fun evaluate(entityPartyId: UUID, amount: SigningAmount, creditorIban: String, rail: String): SigningEvaluation {
        val register = liveMandates(entityPartyId)
        val active = register.mapTo(mutableSetOf()) { it.agentPartyId }
        val policy = repository.findPolicy(entityPartyId) ?: SigningPolicy.derived(entityPartyId, register)
        return SigningPolicyEvaluator.evaluatePayment(
            policy = policy,
            activeSignerIds = active,
            groups = repository.listGroups(entityPartyId).associateBy { it.id },
            trustedIbans = repository.listActivePayees(entityPartyId).mapTo(mutableSetOf()) { it.iban },
            payment = PaymentFacts(amount, Iban.normalize(creditorIban), rail),
        )
    }

    // ---------------------------------------------------------------- creation

    suspend fun createPayment(cmd: PaymentApprovalCommand): ApprovalRequest {
        // Natural-key idempotency: the initiator's single-use SCA challenge. A retry of a request
        // that was already created replays it; the same challenge for anything else is refused.
        repository.findBySignatureChallenge(cmd.initiatorScaChallengeId)?.let { existing ->
            if (existing.entityPartyId == cmd.entityPartyId && existing.initiatorPartyId == cmd.initiatorPartyId &&
                existing.kind == ApprovalKind.PAYMENT
            ) {
                return existing
            }
            throw refused(CONFLICT, "SCA_CHALLENGE_USED", "that SCA challenge has already authorised another request")
        }
        val now = clock.instant()
        val evaluation = evaluate(cmd.entityPartyId, cmd.amount, cmd.creditorIban, cmd.rail)
        requireEligibleInitiator(cmd.initiatorPartyId, evaluation)
        requireSatisfiable(evaluation)
        when (sca.verifyConsumedInitiatorChallenge(cmd.initiatorScaChallengeId, cmd.initiatorPartyId)) {
            ScaVerdict.VERIFIED -> Unit
            ScaVerdict.REFUSED -> throw refused(FORBIDDEN, "SCA_NOT_VERIFIED", "initiator SCA challenge is not a consumed payment approval of this party")
            ScaVerdict.UNAVAILABLE -> throw refused(UNAVAILABLE, "SCA_UNAVAILABLE", "sca-service could not be reached")
        }
        val payload = codec.canonical(cmd.payload)
        val summary = codec.canonical(
            mapOf(
                "amount" to cmd.amount.amount.toPlainString(),
                "currency" to cmd.amount.currency,
                "creditorIban" to Iban.normalize(cmd.creditorIban),
                "creditorName" to cmd.creditorName,
                "rail" to cmd.rail,
            ),
        )
        val base = newRequest(cmd.entityPartyId, ApprovalKind.PAYMENT, payload, summary, evaluation, cmd.initiatorPartyId, now, cmd.ttl)
            .withNames()
        // The initiator's own SCA is the first signature (ADR-0312).
        val request = base.sign(cmd.initiatorPartyId, cmd.initiatorScaChallengeId, now)
        val events = mutableListOf(event(ApprovalRequested.EVENT_TYPE, request, cmd.initiatorPartyId, now))
        if (request.status == ApprovalStatus.APPROVED) events += event(ApprovalCompleted.EVENT_TYPE, request, null, now)
        repository.create(request, events)
        return request
    }

    suspend fun proposePolicy(entityPartyId: UUID, initiatorPartyId: UUID, rules: List<SigningPolicyRule>, cap: SigningAmount?): ApprovalRequest {
        // Validates the proposal before anyone is asked to sign it.
        SigningPolicy(entityPartyId = entityPartyId, version = 1, rules = rules, trustedPayeeCap = cap)
        val payload = mapOf("change" to "POLICY", "rules" to rules.map { it.toMap() }, "trustedPayeeCap" to cap?.toMap())
        return createAdministrative(entityPartyId, ApprovalKind.POLICY_CHANGE, initiatorPartyId, payload)
    }

    suspend fun proposeGroup(entityPartyId: UUID, initiatorPartyId: UUID, group: SignerGroup): ApprovalRequest {
        @Suppress("UNCHECKED_CAST")
        val pending = openAdministrative(entityPartyId, ApprovalKind.POLICY_CHANGE)
            .any { (codec.parseObject(it.payload)["group"] as Map<String, Any?>?)?.get("id") == group.id }
        if (pending) throw refused(CONFLICT, "SIGNER_GROUP_CHANGE_PENDING", "a change to that group is already waiting for signatures")
        val payload = mapOf(
            "change" to "SIGNER_GROUP",
            "group" to mapOf("id" to group.id, "name" to group.name, "memberPartyIds" to group.memberPartyIds.map { it.toString() }.sorted()),
        )
        return createAdministrative(entityPartyId, ApprovalKind.POLICY_CHANGE, initiatorPartyId, payload)
    }

    suspend fun proposePayeeAdd(entityPartyId: UUID, initiatorPartyId: UUID, iban: String, name: String, bic: String?): ApprovalRequest {
        require(name.isNotBlank() && name.length <= MAX_PAYEE_NAME) { "name must be 1..$MAX_PAYEE_NAME characters" }
        val normalized = Iban.normalize(iban)
        if (repository.listActivePayees(entityPartyId).any { it.iban == normalized }) {
            throw refused(CONFLICT, "PAYEE_ALREADY_TRUSTED", "that account is already a trusted payee")
        }
        if (openAdministrative(entityPartyId, ApprovalKind.PAYEE_ADD).any { codec.parseObject(it.payload)["iban"] == normalized }) {
            throw refused(CONFLICT, "PAYEE_CHANGE_PENDING", "adding that account is already waiting for signatures")
        }
        val payload = mapOf("iban" to normalized, "name" to name.trim(), "bic" to bic?.trim()?.uppercase())
        return createAdministrative(entityPartyId, ApprovalKind.PAYEE_ADD, initiatorPartyId, payload)
    }

    suspend fun proposePayeeRemove(entityPartyId: UUID, initiatorPartyId: UUID, payeeId: UUID): ApprovalRequest {
        val payee = repository.findPayee(entityPartyId, payeeId)?.takeIf { it.status == TrustedPayeeStatus.ACTIVE }
            ?: throw refused(NOT_FOUND, "PAYEE_NOT_FOUND", "trusted payee $payeeId not found")
        val payload = mapOf("trustedPayeeId" to payee.id.toString(), "iban" to payee.iban, "name" to payee.name)
        return createAdministrative(entityPartyId, ApprovalKind.PAYEE_REMOVE, initiatorPartyId, payload)
    }

    /**
     * POLICY_CHANGE / PAYEE_ADD / PAYEE_REMOVE need the policy's FULL round — the strictest rule —
     * and no signature is recorded at creation: the initiator signs through [sign] like everyone
     * else, with a challenge linked to this request's id and hash.
     */
    private suspend fun createAdministrative(
        entityPartyId: UUID,
        kind: ApprovalKind,
        initiatorPartyId: UUID,
        payload: Map<String, Any?>,
    ): ApprovalRequest {
        val now = clock.instant()
        val register = liveMandates(entityPartyId)
        val active = register.mapTo(mutableSetOf()) { it.agentPartyId }
        val policy = repository.findPolicy(entityPartyId) ?: SigningPolicy.derived(entityPartyId, register)
        val evaluation = SigningPolicyEvaluator.evaluateAdministrative(policy, active, repository.listGroups(entityPartyId).associateBy { it.id })
        requireEligibleInitiator(initiatorPartyId, evaluation)
        requireSatisfiable(evaluation)
        val canonical = codec.canonical(payload)
        val request = newRequest(entityPartyId, kind, canonical, canonical, evaluation, initiatorPartyId, now, null).withNames()
            .copy(status = ApprovalStatus.AWAITING_INITIATOR, expiresAt = now.plus(Duration.ofMinutes(ApprovalRequest.AWAITING_INITIATOR_TTL_MINUTES)))
        // No event: co-signers hear about it only once the initiator has signed (contract pin 1).
        repository.create(request, emptyList())
        return request
    }

    private suspend fun openAdministrative(entityPartyId: UUID, kind: ApprovalKind): List<ApprovalRequest> =
        (repository.list(entityPartyId, ApprovalStatus.AWAITING_INITIATOR, LIST_LIMIT) + repository.list(entityPartyId, ApprovalStatus.PENDING, LIST_LIMIT))
            .filter { it.kind == kind && !it.isExpiredAt(clock.instant()) }

    // ---------------------------------------------------------------- reads

    suspend fun get(entityPartyId: UUID, id: UUID): ApprovalRequest =
        repository.find(entityPartyId, id) ?: throw refused(NOT_FOUND, "APPROVAL_NOT_FOUND", "approval request $id not found")

    suspend fun list(entityPartyId: UUID, status: ApprovalStatus?, signer: UUID?, initiator: UUID?): List<ApprovalRequest> =
        repository.list(entityPartyId, status, LIST_LIMIT)
            .filter { signer == null || signer in it.eligibleSignerIds || signer == it.initiatorPartyId }
            .filter { initiator == null || it.initiatorPartyId == initiator }

    /** Everything waiting for THIS human's signature, across every entity they can sign for now. */
    suspend fun pendingFor(humanPartyId: UUID): PendingForHuman {
        val now = clock.instant()
        val entities = mandates.actingFor(humanPartyId)
        if (entities.isEmpty()) return PendingForHuman(emptyList(), emptyList())
        val names = entities.associate { it.entityPartyId to it.name }
        val items = repository.listPending(names.keys, LIST_LIMIT)
            .filter { humanPartyId in it.eligibleSignerIds && humanPartyId !in it.signerIds && !it.isExpiredAt(now) }
            .sortedBy { it.expiresAt }
        val grouped = items.groupBy { it.entityPartyId }.map { (entity, rows) ->
            PendingEntity(entity, names[entity], rows.size, rows.minOf { it.expiresAt })
        }.sortedBy { it.oldestExpiresAt }
        return PendingForHuman(grouped, items)
    }

    // ---------------------------------------------------------------- signing

    suspend fun sign(entityPartyId: UUID, id: UUID, partyId: UUID, scaChallengeId: UUID): ApprovalRequest {
        val now = clock.instant()
        val current = get(entityPartyId, id)
        // Cheap guards first, so a doomed attempt never spends the signer's SCA ceremony.
        signatureGuard { current.sign(partyId, scaChallengeId, now) }
        requireLiveMandate(entityPartyId, partyId)
        val link = ApprovalLink(
            approvalRequestId = current.id,
            payloadSha256 = current.payloadSha256,
            amount = current.paymentAmount(),
            creditorIban = current.paymentCreditorIban(),
        )
        when (sca.consumeApprovalChallenge(scaChallengeId, partyId, link)) {
            ScaVerdict.VERIFIED -> Unit
            ScaVerdict.REFUSED -> throw refused(FORBIDDEN, "SCA_NOT_LINKED", "SCA challenge is not linked to this approval request and payload")
            ScaVerdict.UNAVAILABLE -> throw refused(UNAVAILABLE, "SCA_UNAVAILABLE", "sca-service could not be reached")
        }
        return repository.transition(entityPartyId, id) { fresh, context ->
            val first = fresh.status == ApprovalStatus.AWAITING_INITIATOR
            val signed = signatureGuard { fresh.sign(partyId, scaChallengeId, now) }.let {
                // The initiator's confirmation opens the real round: its full lifetime starts now.
                if (first) it.copy(expiresAt = now.plus(Duration.ofHours(ApprovalRequest.DEFAULT_TTL_HOURS))) else it
            }
            val type = if (first) ApprovalRequested.EVENT_TYPE else ApprovalSigned.EVENT_TYPE
            val events = mutableListOf<DomainEvent>(event(type, signed, partyId, now))
            if (signed.status != ApprovalStatus.APPROVED) return@transition Transition(signed, events)
            completion(signed, context, now, events)
        }
    }

    suspend fun reject(entityPartyId: UUID, id: UUID, partyId: UUID, reason: String?): ApprovalRequest {
        val now = clock.instant()
        val trimmed = reason?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmed == null || trimmed.length <= MAX_REASON) { "reason must be at most $MAX_REASON characters" }
        signatureGuard { get(entityPartyId, id).reject(partyId, trimmed, now) }
        requireLiveMandate(entityPartyId, partyId)
        return repository.transition(entityPartyId, id) { fresh, _ ->
            val rejected = signatureGuard { fresh.reject(partyId, trimmed, now) }
            Transition(rejected, listOf(event(ApprovalRejected.EVENT_TYPE, rejected, partyId, now)))
        }
    }

    // ---------------------------------------------------------------- release

    /**
     * The single-use release. Mandates are re-checked LIVE for every counted signer before the CAS:
     * a signer removed from the register since signing no longer counts, and a round that drops
     * below N is refused rather than executed.
     */
    suspend fun claimRelease(entityPartyId: UUID, id: UUID): ReleaseClaim {
        val now = clock.instant()
        val request = get(entityPartyId, id)
        if (request.kind != ApprovalKind.PAYMENT) throw refused(CONFLICT, "NOT_RELEASABLE", "only a payment is released")
        when {
            request.status == ApprovalStatus.RELEASED || request.status == ApprovalStatus.RELEASE_FAILED ->
                throw refused(CONFLICT, "ALREADY_CLAIMED", "approval request $id was already released")
            request.status != ApprovalStatus.APPROVED ->
                throw refused(CONFLICT, "NOT_APPROVED", "approval request $id is ${request.status}")
            request.isExpiredAt(now) -> throw refused(CONFLICT, "EXPIRED", "approval request $id expired at ${request.expiresAt}")
        }
        val active = liveMandates(entityPartyId).mapTo(mutableSetOf()) { it.agentPartyId }
        val stillValid = request.copy(signatures = request.signatures.filter { it.partyId in active })
        if (!stillValid.satisfied) {
            throw refused(CONFLICT, "MANDATE_LAPSED", "a signer no longer holds an active mandate; the round is incomplete")
        }
        val token = UUID.randomUUID()
        if (!repository.claimRelease(entityPartyId, id, token, now)) {
            throw refused(CONFLICT, "ALREADY_CLAIMED", "approval request $id was already released")
        }
        return ReleaseClaim(token, request.payload)
    }

    suspend fun recordReleaseResult(
        entityPartyId: UUID,
        id: UUID,
        ok: Boolean,
        releaseRef: String?,
        error: String?,
        claimToken: UUID?,
    ): ApprovalRequest {
        val now = clock.instant()
        if (ok) require(!releaseRef.isNullOrBlank() && releaseRef.length <= MAX_REF) { "releaseRef is required when ok" }
        val message = error?.take(MAX_REASON)
        return repository.transition(entityPartyId, id) { fresh, _ ->
            if (claimToken != null && fresh.claimToken != claimToken) {
                throw refused(CONFLICT, "CLAIM_TOKEN_MISMATCH", "claim token does not match the winning claim")
            }
            val recorded = fresh.releaseRef != null || fresh.status == ApprovalStatus.RELEASE_FAILED
            if (fresh.status != ApprovalStatus.RELEASED && fresh.status != ApprovalStatus.RELEASE_FAILED) {
                throw refused(CONFLICT, "NOT_CLAIMED", "approval request $id has not been claimed")
            }
            if (recorded) {
                val same = if (ok) fresh.releaseRef == releaseRef else fresh.status == ApprovalStatus.RELEASE_FAILED
                if (!same) throw refused(CONFLICT, "RESULT_ALREADY_RECORDED", "a different release result is already recorded")
                return@transition Transition(fresh, emptyList())
            }
            if (ok) {
                val done = fresh.copy(releaseRef = releaseRef)
                Transition(done, listOf(event(PaymentReleased.EVENT_TYPE, done, null, now, releaseRef = releaseRef)))
            } else {
                val failed = fresh.copy(status = ApprovalStatus.RELEASE_FAILED, releaseError = message ?: "rail rejected the payment")
                Transition(failed, listOf(event(PaymentReleaseFailed.EVENT_TYPE, failed, null, now, reason = failed.releaseError)))
            }
        }
    }

    // ---------------------------------------------------------------- expiry

    /** Called by the scheduler. Expired = nothing executes: PENDING, and APPROVED-but-unclaimed payments. */
    suspend fun expireDue(): Int {
        val now = clock.instant()
        var expired = 0
        for (candidate in repository.findExpirable(now, EXPIRY_BATCH)) {
            val result = repository.transition(candidate.entityPartyId, candidate.id) { fresh, _ ->
                val expirable = fresh.isExpiredAt(now) &&
                    (
                        fresh.status == ApprovalStatus.PENDING || fresh.status == ApprovalStatus.AWAITING_INITIATOR ||
                            (fresh.status == ApprovalStatus.APPROVED && fresh.kind == ApprovalKind.PAYMENT)
                        )
                if (!expirable) return@transition Transition(fresh, emptyList())
                val next = fresh.copy(status = ApprovalStatus.EXPIRED)
                // An unsigned request was never announced, so its expiry is silent too.
                val events = if (fresh.status == ApprovalStatus.AWAITING_INITIATOR) emptyList() else listOf(event(ApprovalExpired.EVENT_TYPE, next, null, now))
                Transition(next, events)
            }
            if (result.status == ApprovalStatus.EXPIRED) expired++
        }
        return expired
    }

    // ---------------------------------------------------------------- internals

    /**
     * An administrative change is applied in the SAME transaction as its last signature. A policy
     * change prepared against a version that has since moved is refused as SUPERSEDED, never
     * applied over a newer policy.
     */
    private fun completion(
        signed: ApprovalRequest,
        context: TransitionContext,
        now: Instant,
        events: MutableList<DomainEvent>,
    ): Transition {
        val payload = codec.parseObject(signed.payload)
        val change: AppliedChange? = when (signed.kind) {
            ApprovalKind.PAYMENT -> null
            ApprovalKind.PAYEE_ADD -> AppliedChange.AddPayee(
                TrustedPayee(
                    id = UUID.randomUUID(),
                    entityPartyId = signed.entityPartyId,
                    iban = payload["iban"] as String,
                    name = payload["name"] as String,
                    bic = payload["bic"] as String?,
                    addedAt = now,
                    addedByApprovalId = signed.id,
                    status = TrustedPayeeStatus.ACTIVE,
                ),
            )
            ApprovalKind.PAYEE_REMOVE -> AppliedChange.RemovePayee(UUID.fromString(payload["trustedPayeeId"] as String), signed.id, now)
            ApprovalKind.POLICY_CHANGE -> {
                if (context.currentPolicyVersion != signed.policyVersion) {
                    val superseded = signed.copy(
                        status = ApprovalStatus.REJECTED,
                        rejection = ApprovalRejection(null, SUPERSEDED, now),
                    )
                    events += event(ApprovalRejected.EVENT_TYPE, superseded, null, now, reason = SUPERSEDED)
                    return Transition(superseded, events)
                }
                policyChange(signed, payload, now)
            }
        }
        events += event(ApprovalCompleted.EVENT_TYPE, signed, null, now)
        return Transition(signed, events, change)
    }

    @Suppress("UNCHECKED_CAST")
    private fun policyChange(signed: ApprovalRequest, payload: Map<String, Any?>, now: Instant): AppliedChange =
        when (payload["change"]) {
            "POLICY" -> AppliedChange.ReplacePolicy(
                SigningPolicy(
                    entityPartyId = signed.entityPartyId,
                    version = signed.policyVersion + 1,
                    rules = (payload["rules"] as List<Map<String, Any?>>).map { ruleFromMap(it) },
                    trustedPayeeCap = (payload["trustedPayeeCap"] as Map<String, Any?>?)?.let { amountFromMap(it) },
                    updatedAt = now,
                    updatedByApprovalId = signed.id,
                ),
            )
            "SIGNER_GROUP" -> {
                val group = payload["group"] as Map<String, Any?>
                AppliedChange.UpsertGroup(
                    SignerGroup(
                        id = group["id"] as String,
                        entityPartyId = signed.entityPartyId,
                        name = group["name"] as String,
                        memberPartyIds = (group["memberPartyIds"] as List<String>).mapTo(mutableSetOf()) { UUID.fromString(it) },
                    ),
                    signed.id,
                    now,
                )
            }
            else -> error("unknown policy change ${payload["change"]}")
        }

    private fun newRequest(
        entityPartyId: UUID,
        kind: ApprovalKind,
        payload: String,
        summary: String?,
        evaluation: SigningEvaluation,
        initiatorPartyId: UUID,
        now: Instant,
        ttl: Duration?,
    ): ApprovalRequest {
        val lifetime = ttl ?: Duration.ofHours(ApprovalRequest.DEFAULT_TTL_HOURS)
        require(!lifetime.isNegative && !lifetime.isZero && lifetime <= Duration.ofHours(ApprovalRequest.DEFAULT_TTL_HOURS)) {
            "expiry must be within ${ApprovalRequest.DEFAULT_TTL_HOURS} hours"
        }
        return ApprovalRequest(
            id = UUID.randomUUID(),
            entityPartyId = entityPartyId,
            kind = kind,
            payload = payload,
            payloadSha256 = sha256(payload),
            summary = summary,
            policyVersion = evaluation.policyVersion,
            required = evaluation.required,
            eligibleSignerIds = evaluation.eligibleSignerIds,
            mustIncludeGroupId = evaluation.mustIncludeGroupId,
            mustIncludeSignerIds = evaluation.mustIncludeSignerIds,
            initiatorPartyId = initiatorPartyId,
            signatures = emptyList<ApprovalSignature>(),
            status = ApprovalStatus.PENDING,
            rejection = null,
            expiresAt = now.plus(lifetime),
            createdAt = now,
        )
    }

    private fun event(
        type: String,
        request: ApprovalRequest,
        actor: UUID?,
        now: Instant,
        reason: String? = request.rejection?.reason,
        releaseRef: String? = request.releaseRef,
    ): DomainEvent {
        val signers = request.signatures.map { it.partyId }
        val recipients = when (type) {
            ApprovalRequested.EVENT_TYPE -> (request.eligibleSignerIds - request.signerIds - request.initiatorPartyId)
            else -> request.eligibleSignerIds + request.initiatorPartyId
        }.sorted()
        val summary = request.summary?.let { codec.parseObject(it) }.orEmpty()
        val amount = if (request.kind == ApprovalKind.PAYMENT) summary["amount"] as String? else null
        val currency = if (request.kind == ApprovalKind.PAYMENT) summary["currency"] as String? else null
        val payeeName = (summary["creditorName"] ?: summary["name"]) as String?
        val a = EventArgs(request, actor, recipients, signers, summary, reason, releaseRef, now)
        return when (type) {
            ApprovalRequested.EVENT_TYPE -> a.run {
                ApprovalRequested(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            ApprovalSigned.EVENT_TYPE -> a.run {
                ApprovalSigned(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            ApprovalCompleted.EVENT_TYPE -> a.run {
                ApprovalCompleted(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            ApprovalRejected.EVENT_TYPE -> a.run {
                ApprovalRejected(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            ApprovalExpired.EVENT_TYPE -> a.run {
                ApprovalExpired(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            PaymentReleased.EVENT_TYPE -> a.run {
                PaymentReleased(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            PaymentReleaseFailed.EVENT_TYPE -> a.run {
                PaymentReleaseFailed(r.id, r.entityPartyId, r.kind, r.status, r.required, r.collected, r.initiatorPartyId, actor, recipients, signers, r.expiresAt, r.payloadSha256, summary, reason, releaseRef, now, r.id, type, r.entityName, r.initiatorName, amount, currency, payeeName)
            }
            else -> error("unknown approval event $type")
        }
    }

    private data class EventArgs(
        val r: ApprovalRequest,
        val actor: UUID?,
        val recipients: List<UUID>,
        val signers: List<UUID>,
        val summary: Map<String, Any?>,
        val reason: String?,
        val releaseRef: String?,
        val now: Instant,
    )

    /** Display names for the notification copy only — a lookup failure leaves them null. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun ApprovalRequest.withNames(): ApprovalRequest {
        val entity = try {
            mandates.entityName(entityPartyId)
        } catch (e: Exception) {
            null
        }
        val initiator = try {
            mandates.personName(initiatorPartyId)
        } catch (e: Exception) {
            null
        }
        return copy(entityName = entity?.take(MAX_NAME), initiatorName = initiator?.take(MAX_NAME))
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun liveMandates(entityPartyId: UUID) = try {
        mandates.activeMandates(entityPartyId)
    } catch (e: MandateDirectoryUnavailableException) {
        throw refused(UNAVAILABLE, "MANDATES_UNAVAILABLE", e.message ?: "party-service could not be reached")
    }

    /** The live mandate re-check at signing: the register as it is NOW, not at request creation. */
    private suspend fun requireLiveMandate(entityPartyId: UUID, partyId: UUID) {
        if (liveMandates(entityPartyId).none { it.agentPartyId == partyId }) {
            throw refused(FORBIDDEN, "MANDATE_NOT_ACTIVE", "party does not hold an active mandate for this entity")
        }
    }

    private fun requireEligibleInitiator(initiator: UUID, evaluation: SigningEvaluation) {
        if (initiator !in evaluation.eligibleSignerIds) {
            throw refused(FORBIDDEN, "NOT_ELIGIBLE", "initiator is not an eligible signer for this entity")
        }
    }

    private fun requireSatisfiable(evaluation: SigningEvaluation) {
        if (!evaluation.satisfiable) {
            throw refused(UNPROCESSABLE, "POLICY_UNSATISFIABLE", "the policy needs more distinct eligible signers than the register holds")
        }
    }

    private inline fun <T> signatureGuard(block: () -> T): T = try {
        block()
    } catch (e: SignatureRefusedException) {
        throw when (e.refusal) {
            SignatureRefusal.NOT_ELIGIBLE -> refused(FORBIDDEN, e.refusal.name, e.message ?: "")
            else -> refused(CONFLICT, e.refusal.name, e.message ?: "")
        }
    }

    private fun ApprovalRequest.paymentAmount(): SigningAmount? = if (kind != ApprovalKind.PAYMENT) {
        null
    } else {
        summary?.let { codec.parseObject(it) }?.let { SigningAmount((it["amount"] as String).toBigDecimal(), it["currency"] as String) }
    }

    private fun ApprovalRequest.paymentCreditorIban(): String? =
        if (kind != ApprovalKind.PAYMENT) null else summary?.let { codec.parseObject(it)["creditorIban"] as String? }

    companion object {
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val UNPROCESSABLE = 422
        const val UNAVAILABLE = 503
        const val SUPERSEDED = "SUPERSEDED"
        private const val LIST_LIMIT = 200
        private const val EXPIRY_BATCH = 200
        private const val MAX_REASON = 500
        private const val MAX_REF = 200
        private const val MAX_PAYEE_NAME = 140
        private const val MAX_NAME = 200

        fun refused(status: Int, code: String, message: String) = BusinessSigningException(status, code, message)

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun SigningAmount.toMap(): Map<String, Any?> = mapOf("amount" to amount.toPlainString(), "currency" to currency)

        fun SigningPolicyRule.toMap(): Map<String, Any?> = mapOf(
            "minAmount" to minAmount?.toMap(),
            "maxAmount" to maxAmount?.toMap(),
            "currency" to currency,
            "requiredSignatures" to requiredSignatures,
            "groupId" to groupId,
            "mustIncludeGroupId" to mustIncludeGroupId,
        )

        fun amountFromMap(map: Map<String, Any?>) = SigningAmount(map["amount"].toString().toBigDecimal(), map["currency"] as String)

        @Suppress("UNCHECKED_CAST")
        fun ruleFromMap(map: Map<String, Any?>) = SigningPolicyRule(
            minAmount = (map["minAmount"] as Map<String, Any?>?)?.let { amountFromMap(it) },
            maxAmount = (map["maxAmount"] as Map<String, Any?>?)?.let { amountFromMap(it) },
            currency = map["currency"] as String?,
            requiredSignatures = (map["requiredSignatures"] as Number).toInt(),
            groupId = map["groupId"] as String?,
            mustIncludeGroupId = map["mustIncludeGroupId"] as String?,
        )
    }
}
