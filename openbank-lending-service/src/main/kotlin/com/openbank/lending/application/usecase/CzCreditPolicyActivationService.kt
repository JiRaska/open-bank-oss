// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.CzCreditPolicyRevision
import com.openbank.lending.application.port.out.CzCreditPolicyStore
import com.openbank.libs.decision.PolicyAttribute
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.decision.PolicyOperator
import com.openbank.libs.decision.PolicyRule
import com.openbank.libs.decision.PolicyTableKind
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.util.Hex
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class CzCreditPolicyStatus { PROPOSED, EXECUTED, REJECTED }

data class CzCreditPolicyView(
    val id: UUID,
    val revision: Int,
    val effectiveFrom: LocalDate,
    val contentHash: String,
    val state: CzCreditPolicyStatus,
    val proposedBy: String,
    val proposedAt: OffsetDateTime,
    val decidedBy: String?,
    val decidedAt: OffsetDateTime?,
    val decisionReason: String?,
    val policyJson: String,
)

@ApplicationScoped
class CzCreditPolicyActivationService(
    private val store: CzCreditPolicyStore,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    fun propose(bundle: PolicyBundle, actor: String): Uni<CzCreditPolicyView> {
        require(actor.isNotBlank()) { "Proposer identity is required" }
        validateBundle(bundle)
        val first = bundle.tables.first()
        requireFuture(first.effectiveFrom)
        val payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle)
        val hash = Hex.lower(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)))
        return store.propose(
            CzCreditPolicyRevision(
                id = Ids.newId(),
                revision = first.version,
                effectiveFrom = first.effectiveFrom,
                payload = payload,
                contentHash = hash,
                state = ProposalState.PROPOSED,
                proposedBy = actor,
                proposedAt = OffsetDateTime.now(clock),
            ),
        ).map { it.toView() }
    }

    fun decide(id: UUID, approve: Boolean, actor: String, reason: String?): Uni<CzCreditPolicyView> =
        store.find(id).flatMap { existing ->
            val row = existing ?: throw ResourceNotFoundException("Unknown Czech credit policy proposal")
            if (row.state != ProposalState.PROPOSED) throw alreadyDecided()
            val now = clock.instant()
            val policy = row.policy()
            val proposal = Proposal(id.toString(), policy, row.proposedBy, row.proposedAt.toInstant(), row.state)
            val decision = if (approve) {
                requireFuture(row.effectiveFrom)
                proposal.approve(actor, now, reason).markExecuted(now)
            } else {
                proposal.reject(actor, now, reason)
            }
            store.decide(id, decision).map { updated ->
                if (updated != 1) throw alreadyDecided()
                row.copy(
                    state = decision.state,
                    decidedBy = decision.decidedBy,
                    decidedAt = decision.decidedAt?.atOffset(ZoneOffset.UTC),
                    decisionReason = decision.decisionReason,
                ).toView()
            }
        }

    fun pending(): Uni<List<CzCreditPolicyView>> = store.pending().map { rows -> rows.map { it.toView() } }

    fun active(asOf: LocalDate): Uni<PolicyBundle?> = store.active(asOf).map { it?.policy() }

    private fun alreadyDecided() = ResourceConflictException(
        "The Czech credit policy proposal was already decided",
        "CREDIT_POLICY_ALREADY_DECIDED",
    )

    private fun requireFuture(day: LocalDate) {
        require(day.isAfter(AccountingClock.bank(clock).today())) {
            "A credit policy revision must take effect on a future day"
        }
    }

    private fun CzCreditPolicyRevision.policy(): PolicyBundle = mapper.readValue(payload, PolicyBundle::class.java)

    private fun CzCreditPolicyRevision.toView() = CzCreditPolicyView(
        id = id,
        revision = revision,
        effectiveFrom = effectiveFrom,
        contentHash = contentHash,
        state = CzCreditPolicyStatus.valueOf(state.name),
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        policyJson = payload,
    )

    companion object {
        const val FIRST_STORED_REVISION = 3
    }
}

private val RULE_ID = Regex("[A-Za-z0-9_.-]{1,80}")
private val PRICE_BAND = Regex("[A-Za-z0-9_.-]{1,32}")

private fun validateBundle(bundle: PolicyBundle) {
    val tables = bundle.tables
    val kinds = tables.map { it.kind }
    require(kinds.toSet() == PolicyTableKind.entries.toSet() && kinds.size == PolicyTableKind.entries.size) {
        "Exactly one table of each policy kind is required"
    }
    val first = tables.first()
    require(first.version >= CzCreditPolicyActivationService.FIRST_STORED_REVISION) {
        "Versions 1 and 2 are reserved for the starter policy"
    }
    val consistent = tables.all {
        it.version == first.version &&
            it.effectiveFrom == first.effectiveFrom &&
            it.effectiveTo == null &&
            it.name.isNotBlank()
    }
    require(consistent) { "Policy tables must share one version and effective date" }
    val rules = tables.flatMap { it.rules }
    require(rules.map { it.id }.distinct().size == rules.size && rules.all { RULE_ID.matches(it.id) }) {
        "Policy rule IDs must be unique and use letters, digits, periods, underscores or hyphens"
    }
    require(tables.all { it.rules.isNotEmpty() }) { "Every policy table requires rules" }
    require(tables.all { table -> table.rules.all { validRule(table.kind, it) } }) {
        "A policy rule uses an unsupported input, incompatible operands, or an invalid price band"
    }
    requireRiskEvidence(bundle)
}

private fun requireRiskEvidence(bundle: PolicyBundle) {
    val byKind = bundle.tables.associateBy { it.kind }
    val adverseBureau = byKind.getValue(PolicyTableKind.EXCLUSION).rules.any {
        it.attribute == PolicyAttribute.CUSTOMER_TYPE &&
            it.operator == PolicyOperator.EQ &&
            it.values == setOf("ADVERSE_BUREAU")
    }
    require(adverseBureau) { "A Czech revision must retain the adverse bureau exclusion" }
    val limits = byKind.getValue(PolicyTableKind.AFFORDABILITY).rules
        .filter { it.operator == PolicyOperator.LTE }.map { it.attribute }.toSet()
    require(limits.containsAll(setOf(PolicyAttribute.DSTI, PolicyAttribute.DTI))) {
        "A Czech revision must define both DSTI and DTI limits"
    }
}

private fun validRule(kind: PolicyTableKind, rule: PolicyRule): Boolean {
    val validOperands = when (rule.attribute) {
        PolicyAttribute.VERIFIED_INCOME_MONTHLY,
        PolicyAttribute.EXISTING_DEBT_SERVICE_MONTHLY,
        PolicyAttribute.DTI,
        PolicyAttribute.DSTI,
        PolicyAttribute.AGE_YEARS,
        PolicyAttribute.EMPLOYMENT_TENURE_MONTHS,
        PolicyAttribute.REQUESTED_AMOUNT,
        -> rule.threshold != null &&
            rule.values.isEmpty() &&
            rule.operator != PolicyOperator.IN &&
            rule.operator != PolicyOperator.NOT_IN
        PolicyAttribute.RESIDENCY,
        PolicyAttribute.CUSTOMER_TYPE,
        PolicyAttribute.PRODUCT_TYPE,
        PolicyAttribute.JURISDICTION,
        -> rule.threshold == null &&
            when (rule.operator) {
                PolicyOperator.IN, PolicyOperator.NOT_IN -> rule.values.isNotEmpty()
                PolicyOperator.EQ, PolicyOperator.NEQ -> rule.values.size == 1
                else -> false
            }
        // These vocabulary entries have no source in the current origination projection.
        PolicyAttribute.BUREAU_SCORE_BAND, PolicyAttribute.TERM_MONTHS, PolicyAttribute.CHANNEL -> false
    }
    val validBand = if (kind == PolicyTableKind.PRICING_BAND) {
        rule.band?.let(PRICE_BAND::matches) == true
    } else {
        rule.band == null
    }
    return validOperands && validBand
}
