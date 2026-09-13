// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.money.Money
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0232 D1. LOAN is a deliberate omission in this first slice: no loan-scoped
 * capability ships yet, so a LOAN value would be an offerable resource type with
 * zero grantable capabilities. Adding it later is a backward-compatible enum addition.
 */
enum class DelegationResourceType {
    ACCOUNT,
    SAVINGS_GOAL,
    CARD,
    PAYMENT,
    STATEMENT,
    DOCUMENT,
}

/**
 * ADR-0232 D2 — the closed capability vocabulary. The home of this list is rules.yaml;
 * until the ADR-0223-style codegen exists, this enum is the single source and any
 * addition is a reviewed diff here.
 */
enum class DelegationCapability {
    ACCOUNT_READ_BALANCES,
    ACCOUNT_READ_TRANSACTIONS,
    ACCOUNT_VIEW_DETAILS,
    ACCOUNT_DOWNLOAD_STATEMENTS,
    ACCOUNT_INITIATE_PAYMENT,
    ACCOUNT_PROPOSE_PAYMENT,
    ACCOUNT_MANAGE_BENEFICIARIES,
    ACCOUNT_MANAGE_LIMITS,
    CARD_VIEW,
    CARD_VIEW_TRANSACTIONS,
    CARD_MANAGE_LIMITS,
    CARD_MANAGE_STATUS,
    CARD_MANAGE_CHANNELS,
    SAVINGS_DEPOSIT,
    SAVINGS_WITHDRAW,
    SAVINGS_PROPOSE_WITHDRAW,
    OBJECT_READ,
    DELEGATION_MANAGE,
}

/** ADR-0232 D1/D8 — generalizes account-service's SigningRule. */
enum class ApprovalPolicy {
    SOLO,
    ANY_ONE,
    ALL,
    N_OF_M,
}

enum class DelegationStatus {
    OFFERED,
    ACTIVE,
    SUSPENDED,
    REVOKED,
    DECLINED,
    EXPIRED,
    RENOUNCED,
}

/**
 * ADR-0232 D7 — disclosure shaping for single-object grants (a payment, a statement,
 * a document). Only meaningful on OBJECT resource types; enforced by the aggregate.
 */
data class Exposure(
    val redactionRules: List<String> = emptyList(),
    val maxViews: Int? = null,
    val watermark: Boolean = true,
    val allowDownload: Boolean = false,
) {
    init {
        require(maxViews == null || maxViews > 0) { "maxViews must be positive when set" }
    }
}

data class DelegationGrant(
    val id: UUID = Ids.newId(),
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    /**
     * Counterparty labels SNAPSHOTTED at offer time from the eligibility lookup this service
     * already performs (issue #3604). Deliberately part of the authorisation record rather than a
     * live lookup: a grant says who agreed to what at the moment they agreed, and a later rename
     * must not silently rewrite that. Null for grants created before this field existed, and for
     * a party pid-service returns no usable name for — consumers fall back to the party id.
     */
    val grantorName: String? = null,
    val granteeName: String? = null,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.SOLO,
    val requiredApprovals: Int? = null,
    val perTransactionLimit: Money? = null,
    val dailyLimit: Money? = null,
    val monthlyLimit: Money? = null,
    val exposure: Exposure? = null,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime?,
    val status: DelegationStatus = DelegationStatus.OFFERED,
    /**
     * Monotonic ordering token for this aggregate's lifecycle events.
     *
     * Revision zero is the OFFERED state. Every status transition increments exactly once; the
     * database trigger is authoritative so an older rolling-deployment writer cannot bypass the
     * invariant. Consumers use this value instead of arrival time, which is not an ordering
     * guarantee once an outbox row is retried or a partition is replayed.
     */
    val lifecycleRevision: Long = 0,
    val grantScaSessionId: UUID? = null,
    val acceptScaSessionId: UUID? = null,
    val note: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val closedAt: OffsetDateTime? = null,
    val closedBy: UUID? = null,
    val closedReason: String? = null,
) {
    init {
        require(grantorPartyId != granteePartyId) { "grantor and grantee must differ" }
        require(capabilities.isNotEmpty()) { "DelegationGrant must have at least one capability" }
        val allowed = CAPABILITY_MATRIX.getValue(resourceType)
        require(capabilities.all { it in allowed }) {
            "capabilities ${capabilities - allowed} are not valid for resource type $resourceType"
        }
        require(exposure == null || resourceType in OBJECT_RESOURCE_TYPES) {
            "exposure is only meaningful for object-level grants (PAYMENT, STATEMENT, DOCUMENT)"
        }
        val holdsExecutionRight = capabilities.any { it in EXECUTION_CAPABILITIES }
        require(!holdsExecutionRight || resourceType !in OBJECT_RESOURCE_TYPES) {
            "object-level grants are read-only disclosure, never execution"
        }
        require(approvalPolicy != ApprovalPolicy.N_OF_M || (requiredApprovals != null && requiredApprovals >= 2)) {
            "N_OF_M approval policy requires requiredApprovals >= 2"
        }
        require(validTo == null || validTo.isAfter(validFrom)) { "validTo must be after validFrom" }
        require(lifecycleRevision >= 0) { "lifecycleRevision must not be negative" }
    }

    fun isActiveOn(now: OffsetDateTime): Boolean = status == DelegationStatus.ACTIVE &&
        !now.isBefore(validFrom) &&
        (validTo == null || now.isBefore(validTo))

    fun hasCapability(capability: DelegationCapability): Boolean = capability in capabilities

    /**
     * May this grant cover [capability] for [amount]?
     *
     * A null [amount] means the caller did not say what the action is worth. That is fine for a
     * capability with no ceiling — a read has no sum — and it is NOT fine when the grant carries a
     * [perTransactionLimit]: answering `true` there tells the caller the ceiling is satisfied
     * without ever comparing anything to it, and the ceiling is the whole reason the grantor set it.
     *
     * So an unpriced question against a priced grant is a DENIAL, not coverage (issue #3800). It
     * reads as strict, and the alternative is worse: `customer-edge`'s `hasGrant` omits the amount
     * on every call, so under the old rule the first caller to put a payment path behind a grant
     * would have got `true` for any sum with the limit sitting unread (#3615). Failing closed here
     * makes the ceiling real regardless of which caller forgets, rather than depending on every
     * caller remembering.
     *
     * Unchanged for everything without a ceiling: a grant with `perTransactionLimit == null` still
     * answers on capability alone, so read paths and unpriced grants behave exactly as before.
     */
    fun covers(capability: DelegationCapability, amount: Money?): Boolean {
        if (!hasCapability(capability)) return false
        if (amount == null) return perTransactionLimit == null
        return withinLimits(amount)
    }

    /**
     * The PER-AMOUNT ceiling only. [dailyLimit] and [monthlyLimit] are deliberately not consulted
     * here, and this is no longer the omission it used to be: a cumulative ceiling cannot be
     * decided from an amount alone — it needs the spend already counted inside a window — so it
     * lives in [SpendCeilings.evaluate], which calls THIS function for the per-transaction half so
     * the two can never disagree (ADR-0249 D3).
     *
     * Consequently `covers()`, and the `/check` endpoint built on it, still answer the
     * per-transaction question only. A rail that moves money on a delegated grant must reserve
     * (`POST /{id}/reservations`) rather than merely check: the check is advisory about ONE amount,
     * the reservation is the authoritative claim on the cumulative headroom.
     */
    fun withinLimits(amount: Money): Boolean {
        if (perTransactionLimit == null) return true
        // A currency the ceiling is not denominated in is a DENIAL, not an error. This used to
        // `require`, which threw out of covers() and surfaced as a 500 from POST /check — an
        // authorization question answered with a crash, where a caller retrying gets the same
        // crash and no product service can distinguish it from an outage.
        if (perTransactionLimit.currency != amount.currency) return false
        return amount.amount.compareTo(perTransactionLimit.amount) <= 0
    }

    fun accept(scaSessionId: UUID, now: OffsetDateTime): DelegationGrant {
        check(status == DelegationStatus.OFFERED) { "only an OFFERED grant can be accepted (is $status)" }
        return copy(
            status = DelegationStatus.ACTIVE,
            lifecycleRevision = nextLifecycleRevision(),
            acceptScaSessionId = scaSessionId,
            updatedAt = now,
        )
    }

    fun decline(now: OffsetDateTime): DelegationGrant {
        check(status == DelegationStatus.OFFERED) { "only an OFFERED grant can be declined (is $status)" }
        return copy(
            status = DelegationStatus.DECLINED,
            lifecycleRevision = nextLifecycleRevision(),
            updatedAt = now,
            closedAt = now,
        )
    }

    fun revoke(by: UUID, reason: String, now: OffsetDateTime): DelegationGrant {
        check(
            status == DelegationStatus.ACTIVE ||
                status == DelegationStatus.SUSPENDED ||
                status == DelegationStatus.OFFERED,
        ) {
            "only an OFFERED/ACTIVE/SUSPENDED grant can be revoked (is $status)"
        }
        return copy(
            status = DelegationStatus.REVOKED,
            lifecycleRevision = nextLifecycleRevision(),
            updatedAt = now,
            closedAt = now,
            closedBy = by,
            closedReason = reason,
        )
    }

    fun renounce(now: OffsetDateTime): DelegationGrant {
        check(status == DelegationStatus.ACTIVE || status == DelegationStatus.SUSPENDED) {
            "only an ACTIVE/SUSPENDED grant can be renounced (is $status)"
        }
        return copy(
            status = DelegationStatus.RENOUNCED,
            lifecycleRevision = nextLifecycleRevision(),
            updatedAt = now,
            closedAt = now,
        )
    }

    fun suspend(reason: String, now: OffsetDateTime): DelegationGrant {
        check(status == DelegationStatus.ACTIVE) { "only an ACTIVE grant can be suspended (is $status)" }
        return copy(
            status = DelegationStatus.SUSPENDED,
            lifecycleRevision = nextLifecycleRevision(),
            updatedAt = now,
            closedReason = reason,
        )
    }

    fun reinstate(now: OffsetDateTime): DelegationGrant {
        check(status == DelegationStatus.SUSPENDED) { "only a SUSPENDED grant can be reinstated (is $status)" }
        return copy(
            status = DelegationStatus.ACTIVE,
            lifecycleRevision = nextLifecycleRevision(),
            updatedAt = now,
            closedReason = null,
        )
    }

    fun expire(now: OffsetDateTime): DelegationGrant {
        check(status == DelegationStatus.ACTIVE) { "only an ACTIVE grant can expire (is $status)" }
        return copy(
            status = DelegationStatus.EXPIRED,
            lifecycleRevision = nextLifecycleRevision(),
            updatedAt = now,
            closedAt = now,
        )
    }

    private fun nextLifecycleRevision(): Long = Math.addExact(lifecycleRevision, 1)

    companion object {
        val OBJECT_RESOURCE_TYPES = setOf(
            DelegationResourceType.PAYMENT,
            DelegationResourceType.STATEMENT,
            DelegationResourceType.DOCUMENT,
        )

        /**
         * Capabilities that move money without further approval. D5 requires KycLevel.FULL
         * for these; propose-only variants are deliberately NOT in this set.
         */
        val EXECUTION_CAPABILITIES = setOf(
            DelegationCapability.ACCOUNT_INITIATE_PAYMENT,
            DelegationCapability.SAVINGS_WITHDRAW,
        )

        val CAPABILITY_MATRIX: Map<DelegationResourceType, Set<DelegationCapability>> = mapOf(
            DelegationResourceType.ACCOUNT to setOf(
                DelegationCapability.ACCOUNT_READ_BALANCES,
                DelegationCapability.ACCOUNT_READ_TRANSACTIONS,
                DelegationCapability.ACCOUNT_VIEW_DETAILS,
                DelegationCapability.ACCOUNT_DOWNLOAD_STATEMENTS,
                DelegationCapability.ACCOUNT_INITIATE_PAYMENT,
                DelegationCapability.ACCOUNT_PROPOSE_PAYMENT,
                DelegationCapability.ACCOUNT_MANAGE_BENEFICIARIES,
                DelegationCapability.ACCOUNT_MANAGE_LIMITS,
                DelegationCapability.DELEGATION_MANAGE,
            ),
            DelegationResourceType.SAVINGS_GOAL to setOf(
                DelegationCapability.SAVINGS_DEPOSIT,
                DelegationCapability.SAVINGS_WITHDRAW,
                DelegationCapability.SAVINGS_PROPOSE_WITHDRAW,
            ),
            DelegationResourceType.CARD to setOf(
                DelegationCapability.CARD_VIEW,
                DelegationCapability.CARD_VIEW_TRANSACTIONS,
                DelegationCapability.CARD_MANAGE_LIMITS,
                DelegationCapability.CARD_MANAGE_STATUS,
                DelegationCapability.CARD_MANAGE_CHANNELS,
            ),
            DelegationResourceType.PAYMENT to setOf(DelegationCapability.OBJECT_READ),
            DelegationResourceType.STATEMENT to setOf(DelegationCapability.OBJECT_READ),
            DelegationResourceType.DOCUMENT to setOf(DelegationCapability.OBJECT_READ),
        )
    }
}

sealed class DelegationCheckResult {
    data class Allowed(val grant: DelegationGrant) : DelegationCheckResult()
    data class Denied(val reason: String, val code: String) : DelegationCheckResult()
}
