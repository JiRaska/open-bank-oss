// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/*
 * ADR-0312 — business payment signing. Pure domain: no framework import, no I/O.
 *
 * delegation-service is the single owner of how many people must sign for a legal entity. The
 * register mandates in party-service are the INPUT (who may sign, SOLE/JOINT); this model is the
 * decision. account-service `SigningRule` is deprecated in favour of it.
 */

/** A money amount on the signing boundary: a plain decimal and an ISO-4217 code. */
data class SigningAmount(val amount: BigDecimal, val currency: String) {
    init {
        require(amount.signum() >= 0) { "amount must not be negative" }
        require(CURRENCY.matches(currency)) { "currency must be a 3-letter ISO-4217 code" }
    }

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}

/**
 * One amount band. Matching is first-match in list order: `minAmount <= amount <= maxAmount`
 * (both inclusive, both optional) and the band currency (when set) equals the payment currency.
 * A bound in a different currency never matches — there is no FX conversion on a signing rule.
 */
data class SigningPolicyRule(
    val minAmount: SigningAmount? = null,
    val maxAmount: SigningAmount? = null,
    val currency: String? = null,
    val requiredSignatures: Int,
    val groupId: String? = null,
    val mustIncludeGroupId: String? = null,
) {
    init {
        require(requiredSignatures in 1..MAX_SIGNATURES) { "requiredSignatures must be 1..$MAX_SIGNATURES" }
    }

    fun matches(amount: SigningAmount): Boolean {
        if (currency != null && currency != amount.currency) return false
        val min = minAmount
        if (min != null && (min.currency != amount.currency || amount.amount < min.amount)) return false
        val max = maxAmount
        if (max != null && (max.currency != amount.currency || amount.amount > max.amount)) return false
        return true
    }

    companion object {
        const val MAX_SIGNATURES = 10
    }
}

/**
 * The entity's signing policy. [version] 0 with [derivedFromRegister] = true is the policy
 * DERIVED from the register when nothing is stored; it is never persisted until a POLICY_CHANGE
 * is approved.
 */
data class SigningPolicy(
    val entityPartyId: UUID,
    val version: Int,
    val rules: List<SigningPolicyRule>,
    val trustedPayeeCap: SigningAmount? = null,
    val updatedAt: Instant? = null,
    val updatedByApprovalId: UUID? = null,
    val derivedFromRegister: Boolean = false,
) {
    init {
        require(rules.isNotEmpty()) { "a signing policy needs at least one rule" }
        require(rules.size <= MAX_RULES) { "a signing policy has at most $MAX_RULES rules" }
    }

    /** The strictest rule: the highest N. Used for administrative changes and unmatched amounts. */
    fun strictestRule(): SigningPolicyRule = rules.maxBy { it.requiredSignatures }

    companion object {
        const val MAX_RULES = 20

        /**
         * The register-derived default: SOLE mandates only ⇒ any one representative; any JOINT
         * mandate ⇒ the highest `requiredSignatures` among the JOINT mandates. Conservative by
         * construction — a mixed register never yields fewer signatures than its JOINT clause.
         */
        fun derived(entityPartyId: UUID, mandates: List<RepresentationMandate>): SigningPolicy {
            val joint = mandates.filter { it.authority == MandateAuthority.JOINT }
            val required = if (joint.isEmpty()) 1 else joint.maxOf { it.requiredSignatures.coerceAtLeast(2) }
            return SigningPolicy(
                entityPartyId = entityPartyId,
                version = 0,
                rules = listOf(
                    SigningPolicyRule(requiredSignatures = required.coerceAtMost(SigningPolicyRule.MAX_SIGNATURES)),
                ),
                derivedFromRegister = true,
            )
        }
    }
}

enum class MandateAuthority { SOLE, JOINT }

/** An ACTIVE, in-window representation mandate as party-service reports it right now. */
data class RepresentationMandate(val agentPartyId: UUID, val authority: MandateAuthority, val requiredSignatures: Int)

data class SignerGroup(val id: String, val entityPartyId: UUID, val name: String, val memberPartyIds: Set<UUID>) {
    init {
        require(ID.matches(id)) { "signer group id must match ${ID.pattern}" }
        require(name.isNotBlank() && name.length <= MAX_NAME) { "signer group name must be 1..$MAX_NAME characters" }
        require(memberPartyIds.isNotEmpty()) { "a signer group needs at least one member" }
    }

    companion object {
        val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        const val MAX_NAME = 120
    }
}

enum class TrustedPayeeStatus { ACTIVE, REMOVED }

data class TrustedPayee(
    val id: UUID,
    val entityPartyId: UUID,
    val iban: String,
    val name: String,
    val bic: String?,
    val addedAt: Instant,
    val addedByApprovalId: UUID,
    val status: TrustedPayeeStatus,
)

/** IBAN in the one form every comparison uses: no whitespace, upper case, basic shape checked. */
object Iban {
    private val SHAPE = Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$")

    fun normalize(raw: String): String {
        val compact = raw.filterNot { it.isWhitespace() }.uppercase()
        require(SHAPE.matches(compact)) { "not a valid IBAN" }
        return compact
    }
}

/** What evaluation needs to know about a payment. */
data class PaymentFacts(val amount: SigningAmount, val creditorIban: String, val rail: String)

data class SigningEvaluation(
    val required: Int,
    val eligibleSignerIds: Set<UUID>,
    val mustIncludeGroupId: String?,
    val mustIncludeSignerIds: Set<UUID>,
    val trusted: Boolean,
    val ruleIndex: Int,
    val policyVersion: Int,
    val derivedFromRegister: Boolean,
) {
    /** A round no set of distinct eligible signers can ever complete. */
    val satisfiable: Boolean
        get() = required <= eligibleSignerIds.size &&
            (mustIncludeGroupId == null || mustIncludeSignerIds.any { it in eligibleSignerIds })
}

/**
 * The single evaluation function (ADR-0312). [activeSignerIds] must come from a LIVE mandate read;
 * group membership only ever narrows it — a group member without an active mandate cannot sign.
 */
object SigningPolicyEvaluator {

    fun evaluatePayment(
        policy: SigningPolicy,
        activeSignerIds: Set<UUID>,
        groups: Map<String, SignerGroup>,
        trustedIbans: Set<String>,
        payment: PaymentFacts,
    ): SigningEvaluation {
        val matched = policy.rules.indexOfFirst { it.matches(payment.amount) }
        // No band covers the amount (or currency): fail closed onto the strictest rule.
        val index = if (matched >= 0) matched else policy.rules.indexOf(policy.strictestRule())
        val rule = policy.rules[index]
        val trusted =
            Iban.normalize(payment.creditorIban) in trustedIbans && withinCap(policy.trustedPayeeCap, payment.amount)
        if (trusted) {
            // A trusted payee lowers the COUNT to one — the initiator's own SCA. It is never an
            // SCA exemption (RTS Article 13 is not applied here).
            return SigningEvaluation(
                required = 1,
                eligibleSignerIds = activeSignerIds,
                mustIncludeGroupId = null,
                mustIncludeSignerIds = emptySet(),
                trusted = true,
                ruleIndex = index,
                policyVersion = policy.version,
                derivedFromRegister = policy.derivedFromRegister,
            )
        }
        return forRule(policy, index, rule, activeSignerIds, groups)
    }

    /**
     * A recurring outflow (#10281, ADR-0312 addendum): a standing order is banded by its
     * PER-EXECUTION amount, an SDD mandate by its maximum amount — and one with no maximum (every
     * mandate today: sdd-service stores none) by the strictest rule. Never the trusted-payee
     * shortcut: the trusted-payee cap bounds ONE payment, and a recurring instruction has no
     * cumulative bound, so trust would turn a capped exception into an uncapped standing one.
     */
    fun evaluateRecurring(
        policy: SigningPolicy,
        activeSignerIds: Set<UUID>,
        groups: Map<String, SignerGroup>,
        amount: SigningAmount?,
    ): SigningEvaluation {
        if (amount == null) return evaluateAdministrative(policy, activeSignerIds, groups)
        val matched = policy.rules.indexOfFirst { it.matches(amount) }
        val index = if (matched >= 0) matched else policy.rules.indexOf(policy.strictestRule())
        return forRule(policy, index, policy.rules[index], activeSignerIds, groups)
    }

    /** POLICY_CHANGE / PAYEE_ADD / PAYEE_REMOVE: the strictest round, never a trusted shortcut. */
    fun evaluateAdministrative(
        policy: SigningPolicy,
        activeSignerIds: Set<UUID>,
        groups: Map<String, SignerGroup>,
    ): SigningEvaluation {
        val rule = policy.strictestRule()
        return forRule(policy, policy.rules.indexOf(rule), rule, activeSignerIds, groups)
    }

    private fun forRule(
        policy: SigningPolicy,
        index: Int,
        rule: SigningPolicyRule,
        activeSignerIds: Set<UUID>,
        groups: Map<String, SignerGroup>,
    ): SigningEvaluation {
        val eligible = rule.groupId?.let { id -> groups[id]?.memberPartyIds.orEmpty() intersect activeSignerIds }
            ?: activeSignerIds
        val mustInclude =
            rule.mustIncludeGroupId?.let { id -> groups[id]?.memberPartyIds.orEmpty() intersect activeSignerIds }
                ?: emptySet()
        return SigningEvaluation(
            required = rule.requiredSignatures,
            eligibleSignerIds = eligible,
            mustIncludeGroupId = rule.mustIncludeGroupId,
            mustIncludeSignerIds = mustInclude,
            trusted = false,
            ruleIndex = index,
            policyVersion = policy.version,
            derivedFromRegister = policy.derivedFromRegister,
        )
    }

    private fun withinCap(cap: SigningAmount?, amount: SigningAmount): Boolean =
        cap == null || (cap.currency == amount.currency && amount.amount <= cap.amount)
}

/**
 * [releasable] kinds are held instructions the edge forwards upstream once signed (single-use
 * release claim); the others are administrative changes applied in the transaction of their last
 * signature. STANDING_ORDER and SDD_MANDATE (#10281) set up a RECURRING outflow and are held
 * exactly like a payment.
 */
enum class ApprovalKind(val releasable: Boolean) {
    PAYMENT(true),
    PAYEE_ADD(false),
    PAYEE_REMOVE(false),
    POLICY_CHANGE(false),
    STANDING_ORDER(true),
    SDD_MANDATE(true),
}

/**
 * AWAITING_INITIATOR: an administrative request (POLICY_CHANGE / PAYEE_ADD / PAYEE_REMOVE) created
 * but not yet signed by its own initiator. Nobody is notified and it expires after
 * [ApprovalRequest.AWAITING_INITIATOR_TTL_MINUTES]; the initiator's signature moves it to PENDING.
 */
enum class ApprovalStatus { AWAITING_INITIATOR, PENDING, APPROVED, REJECTED, EXPIRED, RELEASED, RELEASE_FAILED }

data class ApprovalSignature(val partyId: UUID, val scaChallengeId: UUID, val at: Instant)

data class ApprovalRejection(val partyId: UUID?, val reason: String?, val at: Instant)

/** Why a signature was refused — each maps to one guard in [ApprovalRequest.sign]. */
enum class SignatureRefusal { NOT_PENDING, AWAITING_INITIATOR, EXPIRED, NOT_ELIGIBLE, ALREADY_SIGNED }

class SignatureRefusedException(val refusal: SignatureRefusal, message: String) : RuntimeException(message)

/**
 * The approval aggregate. The payload is FROZEN at creation: every signer's SCA is dynamically
 * linked to [id] + [payloadSha256], so what executes is exactly what was signed.
 */
data class ApprovalRequest(
    val id: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val payload: String,
    val payloadSha256: String,
    val summary: String?,
    val policyVersion: Int,
    val required: Int,
    val eligibleSignerIds: Set<UUID>,
    val mustIncludeGroupId: String?,
    val mustIncludeSignerIds: Set<UUID>,
    val initiatorPartyId: UUID,
    val signatures: List<ApprovalSignature>,
    val status: ApprovalStatus,
    val rejection: ApprovalRejection?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val releasedAt: Instant? = null,
    val releaseRef: String? = null,
    val releaseError: String? = null,
    val claimToken: UUID? = null,
    /** Display-name snapshots for notification copy; never an input to a decision. */
    val entityName: String? = null,
    val initiatorName: String? = null,
) {
    val collected: Int get() = signatures.size

    val signerIds: Set<UUID> get() = signatures.mapTo(mutableSetOf()) { it.partyId }

    /** Reached N distinct signatures and, where set, one of them is from the must-include group. */
    val satisfied: Boolean
        get() = collected >= required &&
            (mustIncludeGroupId == null || signatures.any { it.partyId in mustIncludeSignerIds })

    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)

    /**
     * The four signature guards, in order. Each is proven by a sabotage test: remove it and a
     * specific test goes red.
     */
    @Suppress("ThrowsCount") // one throw per guard, by design
    fun sign(partyId: UUID, scaChallengeId: UUID, at: Instant): ApprovalRequest {
        if (status != ApprovalStatus.PENDING && status != ApprovalStatus.AWAITING_INITIATOR) {
            throw SignatureRefusedException(SignatureRefusal.NOT_PENDING, "approval request $id is $status")
        }
        if (isExpiredAt(at)) {
            throw SignatureRefusedException(SignatureRefusal.EXPIRED, "approval request $id expired at $expiresAt")
        }
        if (status == ApprovalStatus.AWAITING_INITIATOR && partyId != initiatorPartyId) {
            // Nobody co-signs before the initiator has confirmed the change with their own SCA.
            throw SignatureRefusedException(SignatureRefusal.AWAITING_INITIATOR, "the initiator has not signed yet")
        }
        if (partyId !in eligibleSignerIds) {
            throw SignatureRefusedException(SignatureRefusal.NOT_ELIGIBLE, "party is not an eligible signer")
        }
        if (partyId in signerIds) {
            // Distinct signers, and the initiator never counts twice.
            throw SignatureRefusedException(SignatureRefusal.ALREADY_SIGNED, "party has already signed")
        }
        val signed = copy(signatures = signatures + ApprovalSignature(partyId, scaChallengeId, at))
        return signed.copy(status = if (signed.satisfied) ApprovalStatus.APPROVED else ApprovalStatus.PENDING)
    }

    fun reject(partyId: UUID, reason: String?, at: Instant): ApprovalRequest {
        if (status != ApprovalStatus.PENDING) {
            throw SignatureRefusedException(SignatureRefusal.NOT_PENDING, "approval request $id is $status")
        }
        if (partyId !in eligibleSignerIds && partyId != initiatorPartyId) {
            throw SignatureRefusedException(SignatureRefusal.NOT_ELIGIBLE, "party is not an eligible signer")
        }
        return copy(status = ApprovalStatus.REJECTED, rejection = ApprovalRejection(partyId, reason, at))
    }

    companion object {
        const val DEFAULT_TTL_HOURS = 72L
        const val AWAITING_INITIATOR_TTL_MINUTES = 15L
    }
}
