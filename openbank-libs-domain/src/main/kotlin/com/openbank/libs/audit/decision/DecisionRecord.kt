// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit.decision

import com.openbank.libs.domain.identifiers.Ids
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * ADR-0322 phase 1 — **types only**. This package defines the shared envelope every automated
 * decision (authorization, fraud, credit, AI-agent) emits as the `payload` of an
 * [com.openbank.libs.audit.AuditEvent]. It deliberately carries no wiring: no interceptor
 * reference, no outbox/transport dependency (ADR-0323, #10926, still open), no service-level
 * producer. `libs-core-purity` requires this — ADR-0317 forbids a core module (this one) from
 * depending on a bounded-context module such as `openbank-libs-lending`, so the credit
 * [com.openbank.libs.decision.PolicyEvaluation] type maps *onto* [DecisionRecord]; this package
 * never depends the other way.
 */

/** Closed set of automated-decision classes (ADR-0322 D1). */
enum class DecisionClass { AUTHZ, FRAUD, CREDIT, AGENT }

/** What produced the decision — an OPA bundle, a decision table, a scoring model or an LLM. */
enum class DecisionEngineKind { OPA, DECISION_TABLE, MODEL, LLM }

/**
 * Identifies the exact engine version that produced the decision, so a historic record replays
 * deterministically against its pinned policy/model/prompt. For `AUTHZ`, [version] is filled from
 * [com.openbank.libs.authz.AuthzDecision.policyVersion] (ADR-0322 D1).
 */
data class DecisionEngine(
    val kind: DecisionEngineKind,
    /** OPA bundle name, decision-table name, model-registry id (ADR-0141) or prompt id (ADR-0148). */
    val id: String,
    /** OPA bundle version, decision-table version, model-registry version or prompt version. */
    val version: String,
) {
    init {
        require(id.isNotBlank()) { "DecisionEngine.id must not be blank" }
        require(version.isNotBlank()) { "DecisionEngine.version must not be blank" }
    }
}

/**
 * A SHA-256 digest, hex-encoded lower-case (64 characters) — never the inputs it summarises
 * (ADR-0322 D3 minimisation). [sha256] canonicalises a `field -> value` map by sorting keys before
 * hashing, so the digest is **stable regardless of the map's insertion/iteration order** — two
 * logically identical input sets always yield the same [InputDigest] even if a producer built its
 * map in a different order.
 */
data class InputDigest(val hex: String) {
    init {
        require(HEX_PATTERN.matches(hex)) {
            "InputDigest must be a 64-character lower-case hex SHA-256 digest, was: '$hex'"
        }
    }

    companion object {
        private val HEX_PATTERN = Regex("^[0-9a-f]{64}$")

        /**
         * Hashes [fields] over a canonical serialisation: entries sorted by key, joined as
         * `key=value` with a `\n` separator, UTF-8 encoded, SHA-256'd. Deterministic across JVMs
         * and across map implementations/insertion order — the only thing that changes the
         * digest is the (key, value) content itself.
         */
        fun sha256(fields: Map<String, String>): InputDigest {
            val canonical = fields.entries
                .sortedBy { it.key }
                .joinToString(separator = "\n") { "${it.key}=${it.value}" }
            val digestBytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            val hex = digestBytes.joinToString(separator = "") { "%02x".format(it) }
            return InputDigest(hex)
        }
    }
}

/**
 * A single machine-readable reason the engine surfaced. Credit maps
 * [com.openbank.libs.decision.PolicyReasonCode] onto [code] by name (`.name`); the other decision
 * classes define their own closed vocabularies at the producer, since ADR-0322 does not mandate
 * one shared reason-code enum across classes.
 */
data class DecisionReason(val code: String, val ruleId: String? = null) {
    init {
        require(code.isNotBlank()) { "DecisionReason.code must not be blank" }
        require(ruleId == null || ruleId.isNotBlank()) { "DecisionReason.ruleId must not be blank when present" }
    }
}

/** Opaque identifier of the affected party or resource — never a display name (ADR-0322 D1/D3). */
data class SubjectRef(val value: String) {
    init {
        require(value.isNotBlank()) { "SubjectRef.value must not be blank" }
    }
}

/**
 * Cross-channel correlation fields (ADR-0226): [traceId] ties the record to log lines for the
 * same request, [correlationId] groups a business flow, [channel] names the ingress
 * ([com.openbank.libs.audit.AuditChannel]) and [actChain] records the RFC 8693 on-behalf-of
 * delegation chain (ADR-0224). All fields are additive and nullable/empty — an absent value means
 * "unknown", never a default channel.
 */
data class DecisionCorrelation(
    val traceId: String? = null,
    val correlationId: String? = null,
    val channel: String? = null,
    val actChain: List<String> = emptyList(),
) {
    init {
        require(traceId == null || traceId.isNotBlank()) {
            "DecisionCorrelation.traceId must not be blank when present"
        }
        require(correlationId == null || correlationId.isNotBlank()) {
            "DecisionCorrelation.correlationId must not be blank when present"
        }
        require(channel == null || channel.isNotBlank()) {
            "DecisionCorrelation.channel must not be blank when present"
        }
        require(actChain.all { it.isNotBlank() }) { "DecisionCorrelation.actChain must not contain blank entries" }
    }
}

/** The GDPR Art. 22 oversight state of an automated decision (ADR-0322 D1). */
sealed interface HumanReview {
    /** No human review applies to this decision. */
    data object None : HumanReview

    /** A human review has been requested but has not concluded. */
    data object Pending : HumanReview

    /** A human reviewed the decision. [by] is an actor id, never a display name. */
    data class Completed(val by: String, val at: Instant) : HumanReview {
        init {
            require(by.isNotBlank()) { "HumanReview.Completed.by must not be blank" }
        }
    }
}

/**
 * Closed retention classes mapped onto the ADR-0118 GDPR data-lifecycle periods.
 *
 *  - [AUDIT_LOG_5Y] — the ADR-0118 default for audit-log records (AML Act §16 + GDPR Art. 5(2)):
 *    `AUTHZ`, `FRAUD` and `AGENT` decisions.
 *  - [CREDIT_EVIDENCE] — `CREDIT` decisions follow ADR-0214 D4 instead: the *stricter* of the
 *    ADR-0118 default and the pinned lending pack's requirement (ADR-0212), which is not a single
 *    fixed duration and is resolved by the credit producer, never by this enum.
 */
enum class DecisionRetentionClass { AUDIT_LOG_5Y, CREDIT_EVIDENCE }

/**
 * A closed, per-[DecisionClass] outcome. Kept as one sealed hierarchy — rather than a bare
 * `String` — so a decision can never carry an outcome value that does not belong to its own
 * class; [DecisionRecord]'s constructor enforces the pairing.
 */
sealed interface DecisionOutcome {
    enum class Authz : DecisionOutcome { ALLOW, DENY }
    enum class Fraud : DecisionOutcome { PASS, REVIEW, BLOCK }
    enum class Credit : DecisionOutcome { APPROVE, REFER, DECLINE }
    enum class Agent : DecisionOutcome { PROPOSED, EXECUTED, REFUSED }
}

/**
 * The shared envelope for every automated decision (ADR-0322 D1). One instance is emitted as the
 * `payload` of an [com.openbank.libs.audit.AuditEvent], over whichever transport ADR-0322 D2
 * assigns to it — this type says nothing about that transport.
 *
 * [decidedAt] is deliberately **not defaulted**. `AuditEvent.timestamp` used to default to
 * [Instant.EPOCH] and 23 of 25 fleet call sites silently took it (#3882); a decision record is the
 * same evidentiary shape, so every constructor call here must state when the decision happened.
 *
 * [outcome] must be the [DecisionOutcome] subtype that matches [decisionClass]
 * (`AUTHZ` -> [DecisionOutcome.Authz], `FRAUD` -> [DecisionOutcome.Fraud],
 * `CREDIT` -> [DecisionOutcome.Credit], `AGENT` -> [DecisionOutcome.Agent]); a mismatched pairing
 * is rejected at construction rather than left to a reader to notice later.
 */
data class DecisionRecord(
    val decisionId: UUID = Ids.newId(),
    val decisionClass: DecisionClass,
    val decidedAt: Instant,
    val inputDigest: InputDigest,
    val engine: DecisionEngine,
    val outcome: DecisionOutcome,
    val reasons: List<DecisionReason> = emptyList(),
    val subjectRef: SubjectRef,
    val correlation: DecisionCorrelation = DecisionCorrelation(),
    val humanReview: HumanReview = HumanReview.None,
    /** `true` when this record committed in the same transaction as the change it explains. */
    val atomic: Boolean,
    val retentionClass: DecisionRetentionClass,
) {
    init {
        val expectedOutcomeType = when (decisionClass) {
            DecisionClass.AUTHZ -> DecisionOutcome.Authz::class
            DecisionClass.FRAUD -> DecisionOutcome.Fraud::class
            DecisionClass.CREDIT -> DecisionOutcome.Credit::class
            DecisionClass.AGENT -> DecisionOutcome.Agent::class
        }
        require(expectedOutcomeType.isInstance(outcome)) {
            "DecisionRecord.outcome must be a ${expectedOutcomeType.simpleName} for decisionClass=$decisionClass, " +
                "was ${outcome::class.simpleName}"
        }
    }
}
