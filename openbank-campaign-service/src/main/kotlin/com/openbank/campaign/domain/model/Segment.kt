// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

/**
 * Deterministic, rule-based segment (ADR-0201 D1/D2): a versioned artifact evaluated against the
 * ADR-0210 silver layer. Rules are a closed DSL — the only SQL in the system is generated here, from
 * typed rules, never accepted from a UI.
 */
data class Segment(val name: String, val version: Int, val rules: List<SegmentRule>) {
    init {
        require(name.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "segment name must be kebab-case" }
        require(version >= 1) { "segment version must be >= 1" }
        require(rules.isNotEmpty()) { "a segment must have at least one rule" }
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

    /** Party lifecycle state as projected in silver (ACTIVE, PENDING_KYC, SUSPENDED, CLOSED). */
    data class PartyStatusIs(val status: String) : SegmentRule() {
        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String {
            params["${paramPrefix}_status"] = status
            return "(aggregate_type = 'PARTY' AND jsonExtractString(state, 'status') = {${paramPrefix}_status:String})"
        }
    }

    /** Tenure: the party's oldest event is at least [minDays] days old. */
    data class TenureAtLeastDays(val minDays: Long) : SegmentRule() {
        init {
            require(minDays >= 0) { "minDays must be >= 0" }
        }
        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String {
            params["${paramPrefix}_days"] = minDays
            return "(aggregate_type = 'PARTY' AND first_seen <= now64(3) - INTERVAL {${paramPrefix}_days:UInt32} DAY)"
        }
    }

    /** Holds at least one account (any product holding visible in silver). */
    data object HasAccount : SegmentRule() {
        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String =
            "(aggregate_type = 'PARTY' AND has_account = 1)"
    }

    /** An ACTIVE consent carrying [scope] exists (evaluated against consent projections). */
    data class HasActiveConsentScope(val scope: String) : SegmentRule() {
        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String {
            params["${paramPrefix}_scope"] = scope
            return "(aggregate_type = 'PARTY' AND has(consent_scopes, {${paramPrefix}_scope:String}))"
        }
    }
}
