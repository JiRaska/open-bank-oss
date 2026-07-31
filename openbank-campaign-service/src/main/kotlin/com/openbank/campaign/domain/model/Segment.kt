// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

/**
 * Deterministic, rule-based segment (ADR-0201 D1/D2): a versioned artifact evaluated against the
 * ADR-0210 silver layer. Rules are a closed DSL — the only SQL in the system is generated here, from
 * typed rules, never accepted from a UI.
 *
 * The silver layer is an EVENT LOG reduced to the latest row per `(aggregate_type, aggregate_id)`,
 * with the domain attributes inside the `payload` JSON — it is not a per-party attribute table.
 * Rules therefore read `payload`, and a rule needing data the layer does not carry is rejected at
 * construction time rather than rendered into SQL that cannot run (issue #2891).
 */
data class Segment(val name: String, val version: Int, val rules: List<SegmentRule>) {
    init {
        require(name.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "segment name must be kebab-case" }
        require(version >= 1) { "segment version must be >= 1" }
        require(rules.isNotEmpty()) { "a segment must have at least one rule" }
        // Fail where the segment is DEFINED, not at enrolment. An unsupported rule used to render
        // SQL that ClickHouse rejected, which the fail-closed evaluator turned into an empty cohort
        // — indistinguishable from "nobody matched" (#2891).
        rules.forEach { rule ->
            require(rule.unsupportedReason == null) {
                "segment rule ${rule::class.simpleName} cannot be evaluated: ${rule.unsupportedReason}"
            }
        }
    }

    /**
     * Renders the rules as a SQL WHERE fragment over `openbank_analytics.silver_current_state`
     * parameterised with bind values — string interpolation of rule *values* is forbidden, so a
     * rule can never become an injection vector.
     */
    fun toWhereClause(): Pair<String, Map<String, Any>> {
        val params = mutableMapOf<String, Any>()
        val clauses = rules.mapIndexed { i, rule -> rule.toSql("p$i", params) }
        return clauses.joinToString(" AND ") to params
    }
}

sealed class SegmentRule {
    abstract fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String

    /**
     * Why this rule cannot run against today's silver layer, or null when it can. A non-null value
     * makes [Segment] reject the rule up front; see each rule for what would have to exist first.
     */
    open val unsupportedReason: String? = null

    /**
     * Party lifecycle state as carried in the latest PARTY event's payload
     * (ACTIVE, PENDING_KYC, SUSPENDED, CLOSED).
     */
    data class PartyStatusIs(val status: String) : SegmentRule() {
        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String {
            params["${paramPrefix}_status"] = status
            return "(aggregate_type = 'PARTY' AND " +
                "JSONExtractString(payload, 'status') = {${paramPrefix}_status:String})"
        }
    }

    /**
     * Tenure: the party's oldest event is at least [minDays] days old.
     *
     * silver_current_state holds only the LATEST event per aggregate, so first-seen has to come from
     * the bronze log it reduces. The subquery selects `aggregate_id` only — no payload parsing — so
     * it stays on the (aggregate_type, aggregate_id) key.
     */
    data class TenureAtLeastDays(val minDays: Long) : SegmentRule() {
        init {
            require(minDays >= 0) { "minDays must be >= 0" }
        }

        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String {
            params["${paramPrefix}_days"] = minDays
            return "(aggregate_type = 'PARTY' AND aggregate_id IN (" +
                "SELECT aggregate_id FROM openbank_analytics.bronze_events " +
                "WHERE aggregate_type = 'PARTY' GROUP BY aggregate_id " +
                "HAVING min(occurred_at) <= now64(3) - INTERVAL {${paramPrefix}_days:UInt32} DAY))"
        }
    }

    /**
     * Holds at least one account.
     *
     * UNSUPPORTED: no ACCOUNT event in analytics carries a partyId (0 of 59 bronze rows, measured
     * 2026-07-31), so the party↔account link does not exist in this layer at all. Supporting this
     * needs account events to carry the owning party — not a cleverer query.
     */
    data object HasAccount : SegmentRule() {
        override val unsupportedReason: String =
            "ACCOUNT events in the analytics layer carry no partyId, so account ownership " +
                "cannot be resolved (issue #2891)"

        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String =
            throw UnsupportedOperationException(unsupportedReason)
    }

    /**
     * An ACTIVE consent carrying [scope] exists.
     *
     * UNSUPPORTED: consent events are not ingested into analytics at all — neither silver nor bronze
     * holds a CONSENT aggregate. This rule was only ever a cohort-narrowing optimisation: the binding
     * consent check is the live per-send call in LiveConsentCheckAdapter (ADR-0198), which is
     * unaffected. A campaign without this rule is not less consent-gated.
     */
    data class HasActiveConsentScope(val scope: String) : SegmentRule() {
        override val unsupportedReason: String =
            "consent events are not ingested into the analytics layer, so consent scopes cannot be " +
                "resolved there; per-send consent is still enforced live (issue #2891)"

        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String =
            throw UnsupportedOperationException(unsupportedReason)
    }
}
