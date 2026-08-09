// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.contact

/**
 * The three contact classes of ADR-0219 D1 — decided once, here, never re-derived per sender.
 *
 * The split exists because "impression" and "send" are not the same thing: Act 480/2004 §7 governs
 * the bank *sending* commercial communications, not the customer opening their own app. Counting a
 * customer-initiated surface render against the weekly send cap would exhaust the customer's
 * protection in a day; not counting promotional renders at all would be spam with extra steps.
 */
enum class ContactClass {
    /** Bank-initiated delivery (email, push, operator message, coach insight): send cap + quiet period. */
    OUTBOUND_SEND,

    /** Personalised promotional content on a customer-initiated surface: separate, higher budget. */
    PROMOTIONAL_IMPRESSION,

    /** Transactional/security/service content and non-personalised defaults: never counted, never gated. */
    SERVICE_EXEMPT,
}

/** Why the gate refused a contact. Every value maps to a distinct, operator-explainable record. */
enum class ContactDenyReason {
    /** The party is on the suppression list for this topic/scope (ADR-0219 D3) — not a consent question. */
    SUPPRESSED_LIST,

    /** No active consent for the marketing scope (ADR-0198, live per-call check). */
    NO_CONSENT,

    /** The rolling send-cap window is exhausted (ADR-0200 D6, platform-scoped by ADR-0219 D1). */
    SEND_CAP_REACHED,

    /** Inside the quiet period (ADR-0200 D6). */
    QUIET_HOURS,

    /** The rolling promotional-impression budget is exhausted (ADR-0219 D1). */
    IMPRESSION_BUDGET_REACHED,

    /** A state port failed — the gate fails CLOSED for gated classes (ADR-0219 D5). */
    GATE_UNAVAILABLE,
}

/** The gate's answer. [allowed] is the only thing a caller may act on; [denyReason] is the audit. */
data class ContactGateDecision(val allowed: Boolean, val denyReason: ContactDenyReason? = null) {
    companion object {
        val ALLOWED = ContactGateDecision(true)
        fun denied(reason: ContactDenyReason) = ContactGateDecision(false, reason)
    }
}

/** The granularity of a suppression entry (ADR-0219 D3): one topic, one scope, or everything. */
enum class SuppressionScope { ALL, SCOPE, TOPIC }

/** ADR-0219 D3 reason codes — the operational "stop" that is not a consent revocation. */
enum class SuppressionReason { CUSTOMER_OPTOUT, COMPLAINT, RM_MANAGED, LEGAL_HOLD, DECEASED }

/**
 * One platform do-not-contact entry. [value] carries the scope or topic name for SCOPE/TOPIC
 * entries and is ignored for ALL. [source] records where the entry came from (preference centre,
 * complaints flow, RM workbench) so a removal can be routed back to its origin.
 */
data class SuppressionEntry(
    val scope: SuppressionScope,
    val value: String?,
    val reason: SuppressionReason,
    val source: String,
) {
    /** True when this entry suppresses a contact in [consentScope] about [topic]. */
    fun covers(consentScope: String, topic: String?): Boolean = when (scope) {
        SuppressionScope.ALL -> true
        SuppressionScope.SCOPE -> value == consentScope
        SuppressionScope.TOPIC -> topic != null && value == topic
    }
}

/**
 * Platform contact policy (ADR-0219 D1). Windows are DURATIONS (rolling), never calendar days:
 * a midnight boundary must not reset protection. Defaults carry ADR-0200 D6's values; only a
 * platform admin may change them (config, never per sender).
 */
data class ContactPolicy(
    val sendCapPerWindow: Int = 2,
    val sendWindowSeconds: Long = DEFAULT_SEND_WINDOW_SECONDS,
    val quietHoursStart: Int = 21,
    val quietHoursEnd: Int = 8,
    val impressionBudgetPerWindow: Int = 1,
    val impressionWindowSeconds: Long = DEFAULT_IMPRESSION_WINDOW_SECONDS,
    val quietZone: String = "Europe/Prague",
) {
    init {
        require(sendCapPerWindow >= 1) { "send cap must be >= 1" }
        require(impressionBudgetPerWindow >= 1) { "impression budget must be >= 1" }
        require(sendWindowSeconds > 0) { "send window must be positive" }
        require(impressionWindowSeconds > 0) { "impression window must be positive" }
        require(quietHoursStart in 0..MAX_HOUR && quietHoursEnd in 0..MAX_HOUR) { "quiet hours are 0..$MAX_HOUR" }
    }

    companion object {
        private const val MAX_HOUR = 23
        private const val SECONDS_PER_HOUR = 3600L
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_WEEK = 7L
        const val DEFAULT_SEND_WINDOW_SECONDS: Long = DAYS_PER_WEEK * HOURS_PER_DAY * SECONDS_PER_HOUR
        const val DEFAULT_IMPRESSION_WINDOW_SECONDS: Long = HOURS_PER_DAY * SECONDS_PER_HOUR
    }
}
