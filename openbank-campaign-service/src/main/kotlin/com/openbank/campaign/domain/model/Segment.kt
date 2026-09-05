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
            return "(upper(aggregate_type) = 'PARTY' AND " +
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
            return "(upper(aggregate_type) = 'PARTY' AND aggregate_id IN (" +
                "SELECT aggregate_id FROM openbank_analytics.bronze_events " +
                "WHERE upper(aggregate_type) = 'PARTY' GROUP BY aggregate_id " +
                "HAVING min(occurred_at) <= now64(3) - INTERVAL {${paramPrefix}_days:UInt32} DAY))"
        }
    }

    /**
     * Has ever held an account.
     *
     * SUPPORTED SINCE 2026-09-05 (#8792). The reason recorded here previously — "no ACCOUNT event
     * carries a partyId (0 of 59 bronze rows, measured 2026-07-31)" — was already stale when it
     * was written. `AccountCreatedEvent` has carried `partyId` since 2026-06-26, five weeks
     * earlier; `KafkaAccountEventPublisher` serialises the whole event with
     * `objectMapper.writeValueAsString`, so the field reaches the wire; and analytics-sink's
     * `PayloadMasker` does not list `partyId` among its PII keys, so it survives into bronze
     * unmasked. The 59 rows behind that measurement predate the field. A claim about data is a
     * claim with a shelf life, and nothing re-checked this one.
     *
     * READS BRONZE, NOT SILVER, AND THAT IS THE WHOLE CARE IN THIS RULE. Silver keeps only the
     * LATEST event per (aggregate_type, aggregate_id), and of the four account events only
     * `AccountCreatedEvent` carries `partyId` — `AccountStatusChanged`, `AccountClosed` and
     * `SavingsWithdrawalApproved` do not. So an account that has ever changed status has a silver
     * row with no `partyId` at all, and a silver-based subquery would silently omit exactly the
     * parties with the most account activity. That is the under-counting failure this rule's own
     * neighbourhood already records: a fail-closed evaluator renders a missing party as "did not
     * match", which is indistinguishable from a correct answer. `TenureAtLeastDays` reaches into
     * bronze for the same reason and says so.
     *
     * WHAT IT DOES NOT MEAN: "currently holds an OPEN account". Excluding closed accounts is
     * expressible — the terminal signals are `event_type = 'AccountClosed'` and a payload
     * `newStatus` of `CLOSED` — but it is a different predicate and it can only shrink a cohort,
     * so it belongs in its own rule, verified against a live warehouse rather than reasoned about
     * from here (#8792).
     */
    data object HasAccount : SegmentRule() {
        override fun toSql(paramPrefix: String, params: MutableMap<String, Any>): String =
            "(upper(aggregate_type) = 'PARTY' AND aggregate_id IN (" +
                "SELECT JSONExtractString(payload, 'partyId') FROM openbank_analytics.bronze_events " +
                "WHERE upper(aggregate_type) = 'ACCOUNT' " +
                "AND JSONExtractString(payload, 'partyId') != ''))"
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
