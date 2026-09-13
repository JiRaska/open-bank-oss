// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import java.time.Instant
import java.time.Period

/**
 * Per-category retention policy for the analytics layer (ADR-0023, finding F6).
 *
 * A flat "keep everything 10 years" rule is a GDPR Art. 5(1)(e) *storage-limitation* problem: not all
 * analytics data carries a 10-year legal obligation, and keeping personal data longer than its lawful
 * basis is itself a finding. This catalogue ties each data category to its **legal basis**, its
 * retention period, and whether it is **erasable** on an Art. 17 request.
 *
 * The AML/accounting record-keeping obligation (the basis for [AnalyticsRetention.BRONZE_MINIMUM] =
 * 10y) overrides erasure (GDPR Art. 17(3)(b)): those rows are *not* erasable while the obligation
 * stands — instead directly-identifying PII is masked at the sink and the [AnalyticsEnvelope.aggregateId]
 * is retained only as a pseudonym. Categories without a statutory hold *are* erasable.
 */
enum class DataCategory {
    /** AML/accounting bookkeeping events — the regulatory record. 10y, not erasable while obligation stands. */
    ACCOUNTING,

    /** KYC/identity verification artefacts — AML retention, typically 10y after relationship ends. */
    KYC,

    /** Consent/marketing-preference history — kept only as long as the consent basis, erasable. */
    CONSENT,

    /** Operational/behavioural analytics (clickstream-like) — short retention, fully erasable. */
    BEHAVIOURAL,

    /** Dead-letter / operational diagnostics — short retention, not a record of truth. */
    OPERATIONAL,
}

/** Legal basis under GDPR Art. 6 / sectoral law that justifies retaining a [DataCategory]. */
enum class LegalBasis {
    /** Art. 6(1)(c) + Art. 17(3)(b): statutory record-keeping (AML/accounting) — overrides erasure. */
    LEGAL_OBLIGATION,

    /** Art. 6(1)(a): the data subject consented; erasable when consent is withdrawn. */
    CONSENT,

    /** Art. 6(1)(f): legitimate interest (e.g. service operation); erasable on objection. */
    LEGITIMATE_INTEREST,
}

data class CategoryPolicy(
    val category: DataCategory,
    val basis: LegalBasis,
    val retention: Period,
    /** Whether an Art. 17 erasure request can delete this category. False ⇒ statutory hold. */
    val erasable: Boolean,
) {
    /**
     * The earliest instant at which a record created at [createdAt] may be deleted. The [retention]
     * is a calendar [Period] (years/months), so it is added in UTC date arithmetic — `Instant.plus`
     * only supports time-based units and would throw on a Period.
     */
    fun expiresAt(createdAt: Instant): Instant = createdAt.atZone(java.time.ZoneOffset.UTC).plus(retention).toInstant()

    /** Whether a record created at [createdAt] is past its retention as of [now]. */
    fun isExpired(createdAt: Instant, now: Instant): Boolean = !now.isBefore(expiresAt(createdAt))
}

/**
 * The single source of truth mapping each [DataCategory] to its policy. Mirrors the ClickHouse TTLs;
 * code and DDL must agree (the 10y floor is [AnalyticsRetention.BRONZE_MINIMUM]).
 */
object RetentionPolicies {

    private val POLICIES: Map<DataCategory, CategoryPolicy> = listOf(
        CategoryPolicy(
            DataCategory.ACCOUNTING,
            LegalBasis.LEGAL_OBLIGATION,
            AnalyticsRetention.BRONZE_MINIMUM,
            erasable = false,
        ),
        CategoryPolicy(
            DataCategory.KYC,
            LegalBasis.LEGAL_OBLIGATION,
            AnalyticsRetention.BRONZE_MINIMUM,
            erasable = false,
        ),
        CategoryPolicy(DataCategory.CONSENT, LegalBasis.CONSENT, Period.ofYears(3), erasable = true),
        CategoryPolicy(DataCategory.BEHAVIOURAL, LegalBasis.LEGITIMATE_INTEREST, Period.ofMonths(13), erasable = true),
        CategoryPolicy(DataCategory.OPERATIONAL, LegalBasis.LEGITIMATE_INTEREST, Period.ofYears(1), erasable = true),
    ).associateBy { it.category }

    fun of(category: DataCategory): CategoryPolicy = POLICIES[category] ?: error("no retention policy for $category")

    /** All categories that an Art. 17 erasure request is allowed to delete. */
    fun erasableCategories(): Set<DataCategory> = POLICIES.values.filter { it.erasable }.map { it.category }.toSet()

    /**
     * Maps an aggregate type to its [DataCategory] so the right policy applies. Conservative default:
     * anything unmapped is treated as [DataCategory.ACCOUNTING] (the strictest, non-erasable hold) so
     * we never under-retain a record that might carry a statutory obligation.
     */
    fun categoryForAggregateType(aggregateType: String): DataCategory = when (aggregateType.uppercase()) {
        "ACCOUNT", "TRANSACTION", "BALANCE", "LEDGER" -> DataCategory.ACCOUNTING
        // KYB is customer due diligence on a legal entity (ADR-0284): the same AML legal basis
        // as KYC, not the accounting default. The default would hold it for the same period, so
        // this changes the declared BASIS rather than the outcome — which is the point: an
        // unclassified aggregate reads exactly like a classified one in every report.
        "PARTY", "KYC", "KYB" -> DataCategory.KYC
        "CONSENT" -> DataCategory.CONSENT
        else -> DataCategory.ACCOUNTING
    }
}
