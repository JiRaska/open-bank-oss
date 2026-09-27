// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit.decision

import com.openbank.libs.domain.identifiers.Ids
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
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

// --- codec helpers ------------------------------------------------------------------------------
//
// Every fromMap()/valueOf()/UUID.fromString()/Instant.parse() call in this file goes through one
// of these so a malformed payload always throws IllegalArgumentException naming the offending
// field, instead of a bare ClassCastException or NullPointerException from an unchecked `as` cast
// that gives no hint which key was wrong (review finding). [req] and [opt] are the ONLY places in
// this file allowed to cast a payload value.

/**
 * Reads a REQUIRED key from a decoded payload map. Throws [IllegalArgumentException] naming
 * [field] (defaults to [key]) when the key is absent, explicitly `null`, or not a [T].
 */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T> req(map: Map<String, Any?>, key: String, field: String = key): T {
    require(map.containsKey(key) && map[key] != null) { "$field: required key '$key' is missing" }
    val value = map[key]
    return value as? T
        ?: throw IllegalArgumentException(
            "$field: key '$key' must be a ${T::class.simpleName}, was ${value!!::class.simpleName}",
        )
}

/**
 * Reads an OPTIONAL key from a decoded payload map. Returns `null` when the key is absent OR
 * explicitly `null` — both encode "unknown" (see the null-policy note on [DecisionRecord.toMap]).
 * Throws [IllegalArgumentException] naming [field] when the key is present, non-null, and not a
 * [T].
 */
@Suppress("UNCHECKED_CAST")
private inline fun <reified T> opt(map: Map<String, Any?>, key: String, field: String = key): T? {
    val value = map[key] ?: return null
    return value as? T
        ?: throw IllegalArgumentException(
            "$field: key '$key' must be a ${T::class.simpleName}, was ${value::class.simpleName}",
        )
}

/** Wraps [java.lang.Enum.valueOf] so an unknown constant names the offending [field]. */
private inline fun <reified E : Enum<E>> parseEnum(name: String, field: String): E = try {
    enumValueOf<E>(name)
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException(
        "$field: unknown ${E::class.simpleName} constant '$name'",
        e,
    )
}

/** Wraps [UUID.fromString] so a malformed UUID names the offending [field]. */
private fun parseUuid(value: String, field: String): UUID = try {
    UUID.fromString(value)
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("$field: not a valid UUID: '$value'", e)
}

/** Wraps [Instant.parse] so a malformed instant names the offending [field]. */
private fun parseInstant(value: String, field: String): Instant = try {
    Instant.parse(value)
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("$field: not a valid ISO-8601 instant: '$value'", e)
}

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

    companion object {
        /** Pure-Kotlin encoding. [ruleId] is OMITTED (absent key) when `null` — see the null-policy note on [DecisionRecord.toMap]. */
        fun DecisionReason.toMap(): Map<String, Any?> = buildMap {
            put("code", code)
            ruleId?.let { put("ruleId", it) }
        }

        /**
         * Inverse of [toMap]. Accepts `ruleId` either absent or explicitly `null` (legacy
         * producers). Throws [IllegalArgumentException] naming the field on a malformed map.
         */
        fun fromMap(map: Map<String, Any?>, field: String = "DecisionReason"): DecisionReason = DecisionReason(
            code = req(map, "code", field = "$field.code"),
            ruleId = opt(map, "ruleId", field = "$field.ruleId"),
        )
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

        /**
         * Inverse of [toMap]. Throws [IllegalArgumentException] naming the offending field on an
         * unknown/missing `type` discriminator, a missing `by`/`at`, a wrongly typed value, or a
         * malformed `at` instant.
         */
        fun fromMap(map: Map<String, Any?>): HumanReview {
            val type = req<String>(map, "type", field = "HumanReview.type")
            return when (type) {
                "NONE" -> None
                "PENDING" -> Pending
                "COMPLETED" -> Completed(
                    by = req(map, "by", field = "HumanReview.Completed.by"),
                    at = parseInstant(
                        req(map, "at", field = "HumanReview.Completed.at"),
                        "HumanReview.Completed.at",
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

        /**
         * Inverse of [toMap]. Throws [IllegalArgumentException] naming the offending field on a
         * missing/wrongly typed `kind`/`name`, an unknown `kind` discriminator, or a `name` that
         * is not a constant of the subtype selected by `kind`.
         */
        fun fromMap(map: Map<String, Any?>): DecisionOutcome {
            val kind = req<String>(map, "kind", field = "DecisionOutcome.kind")
            val name = req<String>(map, "name", field = "DecisionOutcome.name")
            return when (kind) {
                "AUTHZ" -> parseEnum<Authz>(name, "DecisionOutcome.name")
                "FRAUD" -> parseEnum<Fraud>(name, "DecisionOutcome.name")
                "CREDIT" -> parseEnum<Credit>(name, "DecisionOutcome.name")
                "AGENT" -> parseEnum<Agent>(name, "DecisionOutcome.name")
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
         *
         * **Null policy**: [DecisionCorrelation.traceId]/[DecisionCorrelation.correlationId]/
         * [DecisionCorrelation.channel] (and [DecisionReason.ruleId]) are OMITTED from the map —
         * absent key, never an explicit `null` value — when they are `null` on the source object.
         * [fromMap] accepts either shape on read (a key absent, or present and explicitly `null`)
         * so a payload written by an older producer that still emits the explicit-null shape
         * still decodes.
         */
        fun DecisionRecord.toMap(): Map<String, Any?> = buildMap {
            put(KEY_DECISION_ID, decisionId.toString())
            put(KEY_DECISION_CLASS, decisionClass.name)
            put(KEY_DECIDED_AT, decidedAt.toString())
            put(KEY_INPUT_DIGEST, inputDigest.hex)
            put(KEY_ENGINE_KIND, engine.kind.name)
            put(KEY_ENGINE_ID, engine.id)
            put(KEY_ENGINE_VERSION, engine.version)
            put(KEY_OUTCOME, with(DecisionOutcome) { outcome.toMap() })
            put(KEY_REASONS, reasons.map { reason -> with(DecisionReason) { reason.toMap() } })
            put(KEY_SUBJECT_REF, subjectRef.value)
            correlation.traceId?.let { put(KEY_TRACE_ID, it) }
            correlation.correlationId?.let { put(KEY_CORRELATION_ID, it) }
            correlation.channel?.let { put(KEY_CHANNEL, it) }
            put(KEY_ACT_CHAIN, correlation.actChain)
            put(KEY_HUMAN_REVIEW, with(HumanReview) { humanReview.toMap() })
            put(KEY_ATOMIC, atomic)
            put(KEY_RETENTION_CLASS, retentionClass.name)
        }

        /**
         * Inverse of [toMap]. Throws [IllegalArgumentException] naming the offending field —
         * never [NullPointerException] or a bare [ClassCastException] — on a missing key, a
         * wrongly typed value, an unknown enum constant, a malformed UUID, or a malformed
         * ISO-8601 instant, at any level including the nested `reasons`/`outcome`/`humanReview`
         * maps.
         */
        fun fromMap(map: Map<String, Any?>): DecisionRecord {
            val reasonEntries = req<List<*>>(map, KEY_REASONS)
            val reasons = reasonEntries.mapIndexed { index, entry ->
                val reasonMap = entry as? Map<*, *>
                    ?: throw IllegalArgumentException(
                        "$KEY_REASONS[$index]: expected a map but was " +
                            (entry?.let { it::class.simpleName } ?: "null"),
                    )
                @Suppress("UNCHECKED_CAST")
                DecisionReason.fromMap(reasonMap as Map<String, Any?>, field = "$KEY_REASONS[$index]")
            }
            return DecisionRecord(
                decisionId = parseUuid(req(map, KEY_DECISION_ID), KEY_DECISION_ID),
                decisionClass = parseEnum(req(map, KEY_DECISION_CLASS), KEY_DECISION_CLASS),
                decidedAt = parseInstant(req(map, KEY_DECIDED_AT), KEY_DECIDED_AT),
                inputDigest = InputDigest(req(map, KEY_INPUT_DIGEST)),
                engine = DecisionEngine(
                    kind = parseEnum(req(map, KEY_ENGINE_KIND), KEY_ENGINE_KIND),
                    id = req(map, KEY_ENGINE_ID),
                    version = req(map, KEY_ENGINE_VERSION),
                ),
                outcome = DecisionOutcome.fromMap(req(map, KEY_OUTCOME)),
                reasons = reasons,
                subjectRef = SubjectRef(req(map, KEY_SUBJECT_REF)),
                correlation = DecisionCorrelation(
                    traceId = opt(map, KEY_TRACE_ID),
                    correlationId = opt(map, KEY_CORRELATION_ID),
                    channel = opt(map, KEY_CHANNEL),
                    actChain = req(map, KEY_ACT_CHAIN),
                ),
                humanReview = HumanReview.fromMap(req(map, KEY_HUMAN_REVIEW)),
                atomic = req(map, KEY_ATOMIC),
                retentionClass = parseEnum(req(map, KEY_RETENTION_CLASS), KEY_RETENTION_CLASS),
            )
        }
    }
}
