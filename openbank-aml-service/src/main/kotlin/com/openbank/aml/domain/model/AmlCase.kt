// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.model

import java.time.Instant
import java.util.UUID

enum class AmlCaseStatus {
    OPEN,
    UNDER_REVIEW,
    CLEARED,
    BLOCKED,
    ESCALATED,
}

enum class ScreeningType {
    CUSTOMER_ONBOARDING,
    TRANSACTION_MONITORING,
    PERIODIC_REVIEW,
    MANUAL_INVESTIGATION,
}

/**
 * aml-service's name for the shared AML case severity (ADR-0353 D3/D4): one enum, declared once in
 * `openbank-libs-fincrime`, so the rails that open cases cannot drift from it.
 */
typealias AmlRiskLevel = com.openbank.libs.fincrime.AmlCaseSeverity

/**
 * Which entry point opened a case. Set by the code of that entry point and by nothing a caller
 * sends: the create endpoint always records [REST], whatever the request says.
 *
 * It exists because a key proves nothing about who wrote the row under it. A held payment's case
 * key is what a rail accepts a release decision from, so "the case under that key" has to mean
 * "the case the hold intake opened", and only this field can say so.
 */
enum class CaseOrigin {
    /** `POST /api/v1/aml/cases`: a member of staff or a named machine caller. */
    REST,

    /** The `PAYMENT_HELD` consumer: the one case of a held payment. */
    PAYMENT_HOLD_INTAKE,

    /** The post-booking pack evaluation (ADR-0371). */
    PACK_EVALUATION,

    /** Onboarding screening of a new party, from its event or from the reconciler. */
    ONBOARDING_SCREENING,
}

data class AmlCase(
    val id: UUID,
    val idempotencyKey: String,
    /**
     * The party the case is about; `null` while it is not known. A held payment whose rail could not
     * name the debtor's party is recorded that way and resolved from [accountId] afterwards.
     * Never a placeholder and never a copy of [accountId].
     */
    val partyId: UUID?,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: ScreeningType,
    val riskLevel: AmlRiskLevel,
    val status: AmlCaseStatus,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    val decidedBy: String?,
    val screenedAt: Instant,
    val decidedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Request fingerprint the case was created under (#10916); `null` for legacy rows and non-HTTP creates. */
    val requestHash: String? = null,
    /** `null` for rows written before provenance was recorded: unknown, so never treated as a system origin. */
    val origin: CaseOrigin? = null,
) {
    /**
     * False while the owning party is not established: no party at all, or (rows from before a
     * missing party could be stored) the account id standing in for it (#3413).
     */
    val partyResolved: Boolean
        get() = partyId != null && partyId != accountId

    /**
     * The party, or `null` while it is not established. The ONE place that decides what an event
     * may publish as this case's party: every event reads this, never [partyId].
     */
    val establishedPartyId: UUID?
        get() = partyId.takeIf { partyResolved }

    /**
     * A case that sits under a held-payment key without having been opened by the hold intake.
     * The key is reserved on the create endpoint, so such a row predates the reservation or came
     * past it; either way a decision on it would be published under a key a rail releases on.
     */
    val isForeignClaimOnHoldKey: Boolean
        get() = idempotencyKey.startsWith(PAYMENT_HOLD_KEY_PREFIX) && origin != CaseOrigin.PAYMENT_HOLD_INTAKE

    /**
     * A case opened by the post-booking pack evaluation (ADR-0371), as opposed to a
     * `TRANSACTION_MONITORING` case a payment rail opened when a screening gate referred a transfer.
     * Both share the screening type, and they are not the same thing: a rail's case is worked by
     * operators and releases a payment hold; a pack-monitoring case is confidential and four-eyes.
     * The evaluation job is the only writer of keys with [PACK_MONITORING_KEY_PREFIX]; the REST
     * create endpoint refuses them.
     */
    val isPackMonitoringCase: Boolean
        get() = screeningType == ScreeningType.TRANSACTION_MONITORING &&
            idempotencyKey.startsWith(PACK_MONITORING_KEY_PREFIX)

    fun transitionTo(
        targetStatus: AmlCaseStatus,
        decisionReason: String?,
        assignedAnalyst: String?,
        decidedBy: String,
        // No default. It used to be `Instant.EPOCH`, and the one caller that took the default was
        // production (`AmlCaseService.updateDecision`), so every terminal AML decision — an
        // analyst's and the sandbox auto-clear alike — recorded `decidedAt`/`updatedAt` as
        // 1970-01-01, straight into the DB columns and onto `aml.case.status_changed.v1`. Both
        // unit-test callers already passed a real value, so nothing observed it (#5837).
        now: Instant,
    ): AmlCase {
        require(canTransitionTo(targetStatus)) { "Invalid AML case status transition: $status -> $targetStatus" }
        require(decidedBy.isNotBlank()) { "decidedBy is required" }
        require(targetStatus != AmlCaseStatus.BLOCKED || !decisionReason.isNullOrBlank()) {
            "decisionReason is required when blocking an AML case"
        }

        return copy(
            status = targetStatus,
            decisionReason = decisionReason?.trim()?.ifBlank { null },
            assignedAnalyst = assignedAnalyst?.trim()?.ifBlank { null },
            decidedBy = decidedBy.trim(),
            decidedAt = now,
            updatedAt = now,
        )
    }

    fun canTransitionTo(targetStatus: AmlCaseStatus): Boolean = when (status) {
        AmlCaseStatus.OPEN -> targetStatus in setOf(
            AmlCaseStatus.UNDER_REVIEW,
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
            AmlCaseStatus.ESCALATED,
        )

        AmlCaseStatus.UNDER_REVIEW -> targetStatus in setOf(
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
            AmlCaseStatus.ESCALATED,
        )

        AmlCaseStatus.ESCALATED -> targetStatus in setOf(
            AmlCaseStatus.UNDER_REVIEW,
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
        )

        AmlCaseStatus.CLEARED,
        AmlCaseStatus.BLOCKED,
        -> false
    }

    companion object {
        /** Reserved idempotency-key prefix of cases the pack evaluation opens. */
        const val PACK_MONITORING_KEY_PREFIX = "tm:"

        /** Reserved idempotency-key prefix of the one case a held payment opens. */
        const val PAYMENT_HOLD_KEY_PREFIX = "payment-hold:"

        /** Reserved prefix a case is moved under when the hold intake displaces it from a hold key. */
        const val QUARANTINED_KEY_PREFIX = "quarantined:"

        /** Key namespaces only this service's own jobs and consumers write; the create endpoint refuses them. */
        val RESERVED_KEY_PREFIXES = listOf(PACK_MONITORING_KEY_PREFIX, PAYMENT_HOLD_KEY_PREFIX, QUARANTINED_KEY_PREFIX)

        /**
         * What the REST response carries in its REQUIRED `partyId` while the party is not established:
         * the nil UUID. That contract cannot express a missing party without a breaking change, so the
         * absence is stated by `partyResolved = false` next to it and this value only fills the slot.
         * Events are not bound by it and publish `null`. It is never stored and never accepted as a party.
         */
        val UNRESOLVED_PARTY_WIRE_ID: UUID = UUID(0L, 0L)

        fun quarantinedKey(caseId: UUID): String = "$QUARANTINED_KEY_PREFIX$caseId"
    }
}
