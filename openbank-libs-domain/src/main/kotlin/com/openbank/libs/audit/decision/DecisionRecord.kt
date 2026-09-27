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
 * reference, no outbox/transport dependency (the hash-linked publisher proposed in #10926, still open), no service-level
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
         * Hashes [fields] over a canonical, **injective** serialisation: entries sorted by key
         * (`String.compareTo`, i.e. UTF-16 code-unit order, as RFC 8785 §3.2.3 requires for JSON
         * map keys), each entry written length-prefixed as
         * `<utf8 byte length of key>:<key><utf8 byte length of value>:<value>` with no separator
         * between entries. The length prefixes make every entry self-delimiting, so no delimiter
         * character (`=`, `\n`, `:`, …) appearing inside a key or value can ever be misread as a
         * field boundary — unlike a plain `"$key=$value"` join, which is not injective: the flat
         * map `{a: "1\nb=2"}` and the nested pair `{a: "1", b: "2"}` both produced the identical
         * string `a=1\nb=2` under the old scheme, so two different input sets hashed to the same
         * digest.
         */
        fun sha256(fields: Map<String, String>): InputDigest {
            val buffer = java.io.ByteArrayOutputStream()
            fields.entries
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    appendLengthPrefixed(buffer, key)
                    appendLengthPrefixed(buffer, value)
                }
            val digestBytes = MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
            val hex = digestBytes.joinToString(separator = "") { "%02x".format(it) }
            return InputDigest(hex)
        }

        private fun appendLengthPrefixed(buffer: java.io.ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            buffer.write("${bytes.size}:".toByteArray(Charsets.UTF_8))
            buffer.write(bytes)
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

/**
 * The GDPR Art. 22 oversight state of an automated decision (ADR-0322 D1).
 *
 * [type] is an explicit, framework-free discriminator (never `this::class.simpleName`, which
 * is not stable across obfuscation/renaming and is not itself serialisable metadata). This
 * envelope is emitted as [AuditEvent][com.openbank.libs.audit.AuditEvent] `payload`, a plain
 * `Map<String, Any?>` with no Jackson annotation permitted in this module
 * (`libs-core-purity`/ADR-0317) — `data object None` and `data object Pending` carry no
 * properties of their own, so without [type] a codec reading the map back has nothing to
 * distinguish them (or `Completed`) by, and a naive `mapOf("by" to ..., "at" to ...)` shape is
 * ambiguous with an absent-review map. [toMap]/[fromMap] use [type] as the sole discriminator.
 */
sealed interface HumanReview {
    val type: String

    /** No human review applies to this decision. */
    data object None : HumanReview {
        override val type: String = "NONE"
    }

    /** A human review has been requested but has not concluded. */
    data object Pending : HumanReview {
        override val type: String = "PENDING"
    }

    /** A human reviewed the decision. [by] is an actor id, never a display name. */
    data class Completed(val by: String, val at: Instant) : HumanReview {
        override val type: String = "COMPLETED"

        init {
            require(by.isNotBlank()) { "HumanReview.Completed.by must not be blank" }
        }
    }

    companion object {
        /** Pure-Kotlin, framework-free encoding for the [AuditEvent][com.openbank.libs.audit.AuditEvent] payload map. */
        fun HumanReview.toMap(): Map<String, Any?> = when (this) {
            None -> mapOf("type" to type)
            Pending -> mapOf("type" to type)
            is Completed -> mapOf("type" to type, "by" to by, "at" to at.toString())
        }

        /** Inverse of [toMap]. Throws [IllegalArgumentException] on an unknown or missing `type`/field. */
        fun fromMap(map: Map<String, Any?>): HumanReview {
            val type = map["type"] as? String
            return when (type) {
                "NONE" -> None
                "PENDING" -> Pending
                "COMPLETED" -> Completed(
                    by = requireNotNull(map["by"] as? String) { "HumanReview.Completed map missing 'by'" },
                    at = Instant.parse(
                        requireNotNull(map["at"] as? String) {
                            "HumanReview.Completed map missing 'at'"
                        },
                    ),
                )
                else -> throw IllegalArgumentException("Unknown HumanReview type discriminator: '$type'")
            }
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
 *
 * Each subtype's own enum `.name` (`ALLOW`, `PASS`, `APPROVE`, `PROPOSED`, …) is NOT a
 * sufficient discriminator on the wire — `DecisionOutcome.Authz.DENY` and a hypothetical future
 * value sharing the literal string "DENY" in another subtype are otherwise indistinguishable
 * once flattened into the [AuditEvent][com.openbank.libs.audit.AuditEvent] payload map, and
 * [DecisionRecord]'s pairing invariant only holds if the reader can recover which subtype a
 * decoded value belongs to. [kind] carries the subtype name explicitly.
 */
sealed interface DecisionOutcome {
    /** The [DecisionOutcome] subtype this value belongs to — the wire discriminator. */
    val kind: String

    enum class Authz : DecisionOutcome {
        ALLOW,
        DENY,
        ;

        override val kind: String = "AUTHZ"
    }

    enum class Fraud : DecisionOutcome {
        PASS,
        REVIEW,
        BLOCK,
        ;

        override val kind: String = "FRAUD"
    }

    enum class Credit : DecisionOutcome {
        APPROVE,
        REFER,
        DECLINE,
        ;

        override val kind: String = "CREDIT"
    }

    enum class Agent : DecisionOutcome {
        PROPOSED,
        EXECUTED,
        REFUSED,
        ;

        override val kind: String = "AGENT"
    }

    companion object {
        /** Pure-Kotlin, framework-free encoding: `kind` (subtype) + `name` (enum constant). */
        fun DecisionOutcome.toMap(): Map<String, Any?> = mapOf("kind" to kind, "name" to (this as Enum<*>).name)

        /** Inverse of [toMap]. Throws [IllegalArgumentException] on an unknown or missing `kind`/`name`. */
        fun fromMap(map: Map<String, Any?>): DecisionOutcome {
            val kind = requireNotNull(map["kind"] as? String) { "DecisionOutcome map missing 'kind'" }
            val name = requireNotNull(map["name"] as? String) { "DecisionOutcome map missing 'name'" }
            return when (kind) {
                "AUTHZ" -> Authz.valueOf(name)
                "FRAUD" -> Fraud.valueOf(name)
                "CREDIT" -> Credit.valueOf(name)
                "AGENT" -> Agent.valueOf(name)
                else -> throw IllegalArgumentException("Unknown DecisionOutcome kind discriminator: '$kind'")
            }
        }
    }
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

        // Retention pairing documented on DecisionRetentionClass: CREDIT decisions follow the
        // stricter ADR-0214 D4 evidence retention (CREDIT_EVIDENCE); every other decisionClass
        // follows the ADR-0118 default audit-log retention (AUDIT_LOG_5Y). Enforced here rather
        // than left to producers to remember, since a wrong pairing on a CREDIT record would
        // under-retain lending evidence and a wrong pairing on any other class would over-retain
        // beyond the ADR-0118 default with no CREDIT_EVIDENCE basis for it.
        val expectedRetentionClass = when (decisionClass) {
            DecisionClass.CREDIT -> DecisionRetentionClass.CREDIT_EVIDENCE
            DecisionClass.AUTHZ, DecisionClass.FRAUD, DecisionClass.AGENT -> DecisionRetentionClass.AUDIT_LOG_5Y
        }
        require(retentionClass == expectedRetentionClass) {
            "DecisionRecord.retentionClass must be $expectedRetentionClass for decisionClass=$decisionClass, " +
                "was $retentionClass"
        }
    }

    companion object {
        private const val KEY_DECISION_ID = "decisionId"
        private const val KEY_DECISION_CLASS = "decisionClass"
        private const val KEY_DECIDED_AT = "decidedAt"
        private const val KEY_INPUT_DIGEST = "inputDigest"
        private const val KEY_ENGINE_KIND = "engineKind"
        private const val KEY_ENGINE_ID = "engineId"
        private const val KEY_ENGINE_VERSION = "engineVersion"
        private const val KEY_OUTCOME = "outcome"
        private const val KEY_REASONS = "reasons"
        private const val KEY_SUBJECT_REF = "subjectRef"
        private const val KEY_TRACE_ID = "traceId"
        private const val KEY_CORRELATION_ID = "correlationId"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_ACT_CHAIN = "actChain"
        private const val KEY_HUMAN_REVIEW = "humanReview"
        private const val KEY_ATOMIC = "atomic"
        private const val KEY_RETENTION_CLASS = "retentionClass"

        /**
         * Pure-Kotlin, framework-free encoding of the whole envelope for the
         * [AuditEvent][com.openbank.libs.audit.AuditEvent] `payload` map (`libs-core-purity`
         * forbids a Jackson annotation in this module). [DecisionReason] and the nested
         * [HumanReview]/[DecisionOutcome] discriminators round-trip through [fromMap].
         */
        fun DecisionRecord.toMap(): Map<String, Any?> = mapOf(
            KEY_DECISION_ID to decisionId.toString(),
            KEY_DECISION_CLASS to decisionClass.name,
            KEY_DECIDED_AT to decidedAt.toString(),
            KEY_INPUT_DIGEST to inputDigest.hex,
            KEY_ENGINE_KIND to engine.kind.name,
            KEY_ENGINE_ID to engine.id,
            KEY_ENGINE_VERSION to engine.version,
            KEY_OUTCOME to with(DecisionOutcome) { outcome.toMap() },
            KEY_REASONS to reasons.map { mapOf("code" to it.code, "ruleId" to it.ruleId) },
            KEY_SUBJECT_REF to subjectRef.value,
            KEY_TRACE_ID to correlation.traceId,
            KEY_CORRELATION_ID to correlation.correlationId,
            KEY_CHANNEL to correlation.channel,
            KEY_ACT_CHAIN to correlation.actChain,
            KEY_HUMAN_REVIEW to with(HumanReview) { humanReview.toMap() },
            KEY_ATOMIC to atomic,
            KEY_RETENTION_CLASS to retentionClass.name,
        )

        /** Inverse of [toMap]. Throws [IllegalArgumentException]/[NullPointerException] on a malformed map. */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): DecisionRecord {
            val reasons = (map[KEY_REASONS] as List<Map<String, Any?>>).map {
                DecisionReason(code = it["code"] as String, ruleId = it["ruleId"] as? String)
            }
            return DecisionRecord(
                decisionId = UUID.fromString(map[KEY_DECISION_ID] as String),
                decisionClass = DecisionClass.valueOf(map[KEY_DECISION_CLASS] as String),
                decidedAt = Instant.parse(map[KEY_DECIDED_AT] as String),
                inputDigest = InputDigest(map[KEY_INPUT_DIGEST] as String),
                engine = DecisionEngine(
                    kind = DecisionEngineKind.valueOf(map[KEY_ENGINE_KIND] as String),
                    id = map[KEY_ENGINE_ID] as String,
                    version = map[KEY_ENGINE_VERSION] as String,
                ),
                outcome = DecisionOutcome.fromMap(map[KEY_OUTCOME] as Map<String, Any?>),
                reasons = reasons,
                subjectRef = SubjectRef(map[KEY_SUBJECT_REF] as String),
                correlation = DecisionCorrelation(
                    traceId = map[KEY_TRACE_ID] as? String,
                    correlationId = map[KEY_CORRELATION_ID] as? String,
                    channel = map[KEY_CHANNEL] as? String,
                    actChain = map[KEY_ACT_CHAIN] as List<String>,
                ),
                humanReview = HumanReview.fromMap(map[KEY_HUMAN_REVIEW] as Map<String, Any?>),
                atomic = map[KEY_ATOMIC] as Boolean,
                retentionClass = DecisionRetentionClass.valueOf(map[KEY_RETENTION_CLASS] as String),
            )
        }
    }
}
