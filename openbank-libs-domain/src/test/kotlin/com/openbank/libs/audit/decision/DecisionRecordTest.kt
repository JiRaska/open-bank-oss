// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.audit.decision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** Covers ADR-0322 D1: envelope invariants, the outcome/decisionClass pairing, and InputDigest. */
class DecisionRecordTest {

    private val decidedAt: Instant = Instant.parse("2026-09-26T10:00:00Z")

    private fun engine() = DecisionEngine(kind = DecisionEngineKind.OPA, id = "rest.rego", version = "1.4.2")

    private fun digest() = InputDigest.sha256(mapOf("principal" to "party-1", "action" to "account.freeze"))

    private fun retentionFor(decisionClass: DecisionClass) = when (decisionClass) {
        DecisionClass.CREDIT -> DecisionRetentionClass.CREDIT_EVIDENCE
        else -> DecisionRetentionClass.AUDIT_LOG_5Y
    }

    private fun authzRecord(
        outcome: DecisionOutcome = DecisionOutcome.Authz.ALLOW,
        decisionClass: DecisionClass = DecisionClass.AUTHZ,
        retentionClass: DecisionRetentionClass = retentionFor(decisionClass),
    ) = DecisionRecord(
        decisionClass = decisionClass,
        decidedAt = decidedAt,
        inputDigest = digest(),
        engine = engine(),
        outcome = outcome,
        subjectRef = SubjectRef("account-1"),
        atomic = false,
        retentionClass = retentionClass,
    )

    // --- construction / defaults --------------------------------------------------------------

    @Test
    fun `constructs with a matching outcome and defaults`() {
        val record = authzRecord()

        assertThat(record.decisionClass).isEqualTo(DecisionClass.AUTHZ)
        assertThat(record.outcome).isEqualTo(DecisionOutcome.Authz.ALLOW)
        assertThat(record.reasons).isEmpty()
        assertThat(record.correlation).isEqualTo(DecisionCorrelation())
        assertThat(record.humanReview).isEqualTo(HumanReview.None)
        assertThat(record.decisionId).isNotNull()
    }

    @Test
    fun `each construction mints a distinct decisionId when not supplied`() {
        val first = authzRecord()
        val second = authzRecord()

        assertThat(first.decisionId).isNotEqualTo(second.decisionId)
    }

    // --- outcome vs decisionClass pairing (the invariant that matters most) ------------------

    @Test
    fun `rejects a FRAUD outcome on an AUTHZ decision`() {
        assertThatThrownBy { authzRecord(outcome = DecisionOutcome.Fraud.BLOCK) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decisionClass=AUTHZ")
    }

    @Test
    fun `accepts every class paired with its own outcome type`() {
        assertThat(authzRecord(decisionClass = DecisionClass.AUTHZ, outcome = DecisionOutcome.Authz.DENY).outcome)
            .isEqualTo(DecisionOutcome.Authz.DENY)
        assertThat(authzRecord(decisionClass = DecisionClass.FRAUD, outcome = DecisionOutcome.Fraud.REVIEW).outcome)
            .isEqualTo(DecisionOutcome.Fraud.REVIEW)
        assertThat(authzRecord(decisionClass = DecisionClass.CREDIT, outcome = DecisionOutcome.Credit.REFER).outcome)
            .isEqualTo(DecisionOutcome.Credit.REFER)
        assertThat(authzRecord(decisionClass = DecisionClass.AGENT, outcome = DecisionOutcome.Agent.PROPOSED).outcome)
            .isEqualTo(DecisionOutcome.Agent.PROPOSED)
    }

    // --- SubjectRef / DecisionReason / DecisionEngine blank-id invariants ---------------------

    @Test
    fun `rejects a blank subjectRef`() {
        assertThatThrownBy { SubjectRef("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SubjectRef("   ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a blank decision reason code`() {
        assertThatThrownBy { DecisionReason(code = "") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts a decision reason with no ruleId`() {
        val reason = DecisionReason(code = "POLICY_DENY")
        assertThat(reason.ruleId).isNull()
    }

    @Test
    fun `rejects a blank engine id or version`() {
        assertThatThrownBy { DecisionEngine(DecisionEngineKind.OPA, id = "", version = "1.0") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecisionEngine(DecisionEngineKind.OPA, id = "rest.rego", version = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- DecisionCorrelation --------------------------------------------------------------------

    @Test
    fun `rejects a blank traceId, correlationId or channel when present`() {
        assertThatThrownBy { DecisionCorrelation(traceId = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecisionCorrelation(correlationId = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecisionCorrelation(channel = "") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a blank actChain entry`() {
        assertThatThrownBy { DecisionCorrelation(actChain = listOf("agent-1", "")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an absent correlation field means unknown, not a default value`() {
        val correlation = DecisionCorrelation()
        assertThat(correlation.traceId).isNull()
        assertThat(correlation.channel).isNull()
        assertThat(correlation.actChain).isEmpty()
    }

    // --- HumanReview ------------------------------------------------------------------------

    @Test
    fun `rejects a blank Completed by`() {
        assertThatThrownBy { HumanReview.Completed(by = "", at = decidedAt) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `HumanReview None and Pending are stable singletons`() {
        assertThat(HumanReview.None).isSameAs(HumanReview.None)
        assertThat(HumanReview.Pending).isSameAs(HumanReview.Pending)
        assertThat(HumanReview.None).isNotEqualTo(HumanReview.Pending)
    }

    // --- InputDigest --------------------------------------------------------------------------

    @Test
    fun `rejects a digest that is not 64 lower-case hex characters`() {
        assertThatThrownBy { InputDigest("not-a-digest") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InputDigest("A".repeat(64)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InputDigest("a".repeat(63)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts a well-formed 64-character lower-case hex digest`() {
        val digest = InputDigest("a".repeat(64))
        assertThat(digest.hex).hasSize(64)
    }

    @Test
    fun `sha256 is stable regardless of map insertion order`() {
        val ordered = linkedMapOf("action" to "account.freeze", "principal" to "party-1")
        val reversed = linkedMapOf("principal" to "party-1", "action" to "account.freeze")

        assertThat(InputDigest.sha256(ordered)).isEqualTo(InputDigest.sha256(reversed))
    }

    @Test
    fun `sha256 differs when a value changes`() {
        val a = InputDigest.sha256(mapOf("principal" to "party-1"))
        val b = InputDigest.sha256(mapOf("principal" to "party-2"))

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `sha256 produces a digest that InputDigest itself accepts`() {
        val digest = InputDigest.sha256(mapOf("k" to "v"))
        // Re-parsing the produced hex through the primary constructor must not throw.
        assertThat(InputDigest(digest.hex)).isEqualTo(digest)
    }

    // --- sha256 injectivity (security review finding) ------------------------------------------
    //
    // A naive "$key=$value" join with a "\n" separator is NOT injective: a single field whose
    // value embeds the separator and a delimiter can collide with a completely different field
    // set. {a: "1\nb=2"} and {a: "1", b: "2"} both used to serialise to the identical string
    // "a=1\nb=2" and therefore hash to the same digest, even though they are different inputs.

    @Test
    fun `a value embedding the field separator does not collide with a different field set`() {
        val singleFieldWithEmbeddedSeparator = InputDigest.sha256(mapOf("a" to "1\nb=2"))
        val twoDistinctFields = InputDigest.sha256(mapOf("a" to "1", "b" to "2"))

        assertThat(singleFieldWithEmbeddedSeparator).isNotEqualTo(twoDistinctFields)
    }

    @Test
    fun `keys and values containing the encoding's own delimiters round-trip distinctly`() {
        val digests = listOf(
            mapOf("a=b" to "c"),
            mapOf("a" to "=bc"),
            mapOf("a:b" to "c"),
            mapOf("a" to ":bc"),
            mapOf("a\nb" to "c"),
            mapOf("a" to "\nbc"),
            mapOf("1" to "2"),
            mapOf("12" to ""),
            mapOf("1" to "2:3"),
        ).map { InputDigest.sha256(it) }

        assertThat(digests.toSet()).hasSize(digests.size)
    }

    @Test
    fun `a length-prefix-like value does not collide with the field it mimics`() {
        // "1:a" as a value could be mistaken for a length-prefix + 1-char field ("1:a") if entries
        // were concatenated without a length prefix on the *value* too.
        val a = InputDigest.sha256(mapOf("k" to "1:a"))
        val b = InputDigest.sha256(mapOf("k" to "1:a", "extra" to "unrelated"))

        assertThat(a).isNotEqualTo(b)
    }

    // --- equality (data class, value semantics) ------------------------------------------------

    @Test
    fun `two records with identical fields including decisionId are equal`() {
        val id = authzRecord().decisionId
        val a = authzRecord().copy(decisionId = id)
        val b = authzRecord().copy(decisionId = id)

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `records differing only by decisionId are not equal`() {
        val a = authzRecord()
        val b = authzRecord()

        assertThat(a).isNotEqualTo(b)
    }

    // --- retention-class pairing (review finding) -----------------------------------------------
    //
    // DecisionRetentionClass documents CREDIT -> CREDIT_EVIDENCE, every other class -> AUDIT_LOG_5Y
    // (ADR-0214 D4 / ADR-0118). Prose alone does not stop a producer pairing them wrong, so the
    // constructor enforces it.

    @Test
    fun `accepts CREDIT paired with CREDIT_EVIDENCE`() {
        val record = authzRecord(
            decisionClass = DecisionClass.CREDIT,
            outcome = DecisionOutcome.Credit.APPROVE,
            retentionClass = DecisionRetentionClass.CREDIT_EVIDENCE,
        )
        assertThat(record.retentionClass).isEqualTo(DecisionRetentionClass.CREDIT_EVIDENCE)
    }

    @Test
    fun `rejects CREDIT paired with AUDIT_LOG_5Y`() {
        assertThatThrownBy {
            authzRecord(
                decisionClass = DecisionClass.CREDIT,
                outcome = DecisionOutcome.Credit.APPROVE,
                retentionClass = DecisionRetentionClass.AUDIT_LOG_5Y,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("retentionClass")
    }

    @Test
    fun `rejects a non-CREDIT class paired with CREDIT_EVIDENCE`() {
        assertThatThrownBy {
            authzRecord(
                decisionClass = DecisionClass.AUTHZ,
                outcome = DecisionOutcome.Authz.ALLOW,
                retentionClass = DecisionRetentionClass.CREDIT_EVIDENCE,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("retentionClass")
    }

    @Test
    fun `every class accepts only its documented retention pairing`() {
        DecisionClass.entries.forEach { decisionClass ->
            val correct = retentionFor(decisionClass)
            val wrong = DecisionRetentionClass.entries.first { it != correct }
            val outcome = when (decisionClass) {
                DecisionClass.AUTHZ -> DecisionOutcome.Authz.ALLOW
                DecisionClass.FRAUD -> DecisionOutcome.Fraud.PASS
                DecisionClass.CREDIT -> DecisionOutcome.Credit.APPROVE
                DecisionClass.AGENT -> DecisionOutcome.Agent.PROPOSED
            }
            authzRecord(decisionClass = decisionClass, outcome = outcome, retentionClass = correct)
            assertThatThrownBy {
                authzRecord(decisionClass = decisionClass, outcome = outcome, retentionClass = wrong)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    // --- codec round-trips (review finding: framework-free discriminators) ---------------------
    //
    // DecisionRecord is emitted as an AuditEvent payload (Map<String, Any?>), and libs-domain may
    // carry no Jackson import (`libs-core-purity`). HumanReview.None/Pending are property-less
    // objects and DecisionOutcome's sealed subtypes share `.name` values across each other
    // (ALLOW/DENY vs PASS/REVIEW/BLOCK never collide today, but nothing prevented it) -- both are
    // ambiguous on a bare map without an explicit `type`/`kind` discriminator. toMap()/fromMap()
    // are the framework-free codec; these tests are the negative proof for the discriminator: they
    // fail if `type`/`kind` is removed, because fromMap would then have nothing to switch on.

    @Test
    fun `HumanReview None round-trips through toMap fromMap`() {
        val decoded = HumanReview.fromMap(with(HumanReview) { HumanReview.None.toMap() })
        assertThat(decoded).isSameAs(HumanReview.None)
    }

    @Test
    fun `HumanReview Pending round-trips through toMap fromMap`() {
        val decoded = HumanReview.fromMap(with(HumanReview) { HumanReview.Pending.toMap() })
        assertThat(decoded).isSameAs(HumanReview.Pending)
    }

    @Test
    fun `HumanReview Completed round-trips through toMap fromMap`() {
        val completed = HumanReview.Completed(by = "operator-1", at = decidedAt)
        val decoded = HumanReview.fromMap(with(HumanReview) { completed.toMap() })
        assertThat(decoded).isEqualTo(completed)
    }

    @Test
    fun `HumanReview variants encode to distinct discriminated maps`() {
        val maps = listOf(
            with(HumanReview) { HumanReview.None.toMap() },
            with(HumanReview) { HumanReview.Pending.toMap() },
            with(HumanReview) { HumanReview.Completed(by = "operator-1", at = decidedAt).toMap() },
        )
        assertThat(maps.map { it["type"] }.toSet()).hasSize(3)
    }

    @Test
    fun `HumanReview fromMap rejects an unknown type discriminator`() {
        assertThatThrownBy { HumanReview.fromMap(mapOf("type" to "SOMETHING_ELSE")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DecisionOutcome every variant round-trips through toMap fromMap`() {
        val allOutcomes: List<DecisionOutcome> =
            DecisionOutcome.Authz.entries + DecisionOutcome.Fraud.entries +
                DecisionOutcome.Credit.entries + DecisionOutcome.Agent.entries

        allOutcomes.forEach { outcome ->
            val decoded = DecisionOutcome.fromMap(with(DecisionOutcome) { outcome.toMap() })
            assertThat(decoded).isEqualTo(outcome)
        }
    }

    @Test
    fun `DecisionOutcome fromMap rejects an unknown kind discriminator`() {
        assertThatThrownBy { DecisionOutcome.fromMap(mapOf("kind" to "SOMETHING_ELSE", "name" to "ALLOW")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `DecisionRecord round-trips every decisionClass through toMap fromMap`() {
        DecisionClass.entries.forEach { decisionClass ->
            val outcome = when (decisionClass) {
                DecisionClass.AUTHZ -> DecisionOutcome.Authz.DENY
                DecisionClass.FRAUD -> DecisionOutcome.Fraud.REVIEW
                DecisionClass.CREDIT -> DecisionOutcome.Credit.REFER
                DecisionClass.AGENT -> DecisionOutcome.Agent.EXECUTED
            }
            val record = authzRecord(decisionClass = decisionClass, outcome = outcome).copy(
                reasons = listOf(
                    DecisionReason(code = "POLICY_DENY", ruleId = "rule-1"),
                    DecisionReason(code = "OTHER"),
                ),
                correlation = DecisionCorrelation(
                    traceId = "trace-1",
                    correlationId = "corr-1",
                    channel = "api",
                    actChain = listOf("agent-1"),
                ),
                humanReview = HumanReview.Completed(by = "operator-1", at = decidedAt),
            )

            val decoded = DecisionRecord.fromMap(with(DecisionRecord) { record.toMap() })

            assertThat(decoded).isEqualTo(record)
        }
    }

    @Test
    fun `DecisionRecord toMap payload has no ambiguous None-vs-Pending shape`() {
        // Negative proof for the discriminator: without `type`, None and Pending both encode to
        // an effectively empty map and are indistinguishable on decode.
        val record = authzRecord().copy(humanReview = HumanReview.Pending)
        val encoded = with(HumanReview) { record.humanReview.toMap() }
        assertThat(encoded["type"]).isEqualTo("PENDING")
        assertThat(HumanReview.fromMap(encoded)).isSameAs(HumanReview.Pending)
        assertThat(HumanReview.fromMap(encoded)).isNotSameAs(HumanReview.None)
    }

    // --- fromMap: named field, never a bare NPE/CCE (review finding: req/opt helpers) -----------
    //
    // DecisionRecord.fromMap used to cast every field with a bare `as`, so a missing or
    // mistyped key threw a NullPointerException or ClassCastException with no indication which
    // key was wrong. req()/opt() are the only places allowed to cast a decoded payload value, and
    // every failure here must be an IllegalArgumentException naming the key.

    private fun validRecordMap(): MutableMap<String, Any?> {
        val record = authzRecord().copy(
            reasons = listOf(DecisionReason(code = "POLICY_DENY", ruleId = "rule-1")),
            correlation = DecisionCorrelation(traceId = "trace-1", correlationId = "corr-1", channel = "api"),
            humanReview = HumanReview.Completed(by = "operator-1", at = decidedAt),
        )
        return with(DecisionRecord) { record.toMap() }.toMutableMap()
    }

    private fun requiredKeys() = listOf(
        "decisionId", "decisionClass", "decidedAt", "inputDigest", "engineKind", "engineId",
        "engineVersion", "outcome", "reasons", "subjectRef", "actChain", "humanReview", "atomic",
        "retentionClass",
    )

    @Test
    fun `fromMap rejects a missing required key naming that key`() {
        requiredKeys().forEach { key ->
            val map = validRecordMap()
            map.remove(key)
            assertThatThrownBy { DecisionRecord.fromMap(map) }
                .describedAs("dropping '%s' must fail naming it", key)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(key)
        }
    }

    @Test
    fun `fromMap rejects an explicit-null required key naming that key`() {
        requiredKeys().forEach { key ->
            val map = validRecordMap()
            map[key] = null
            assertThatThrownBy { DecisionRecord.fromMap(map) }
                .describedAs("nulling '%s' must fail naming it", key)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(key)
        }
    }

    @Test
    fun `fromMap rejects a wrongly typed required key naming that key`() {
        requiredKeys().forEach { key ->
            val map = validRecordMap()
            // 42 (an Int) is not a valid value for any required key here (String, List or Boolean).
            map[key] = 42
            assertThatThrownBy { DecisionRecord.fromMap(map) }
                .describedAs("mistyping '%s' must fail naming it", key)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(key)
        }
    }

    @Test
    fun `fromMap rejects an unknown decisionClass constant`() {
        val map = validRecordMap()
        map["decisionClass"] = "NOT_A_CLASS"
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decisionClass")
            .hasMessageContaining("NOT_A_CLASS")
    }

    @Test
    fun `fromMap rejects an unknown engineKind constant`() {
        val map = validRecordMap()
        map["engineKind"] = "NOT_AN_ENGINE"
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("engineKind")
            .hasMessageContaining("NOT_AN_ENGINE")
    }

    @Test
    fun `fromMap rejects an unknown retentionClass constant`() {
        val map = validRecordMap()
        map["retentionClass"] = "NOT_A_RETENTION"
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("retentionClass")
            .hasMessageContaining("NOT_A_RETENTION")
    }

    @Test
    fun `fromMap rejects a malformed decisionId UUID`() {
        val map = validRecordMap()
        map["decisionId"] = "not-a-uuid"
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decisionId")
    }

    @Test
    fun `fromMap rejects a malformed decidedAt instant`() {
        val map = validRecordMap()
        map["decidedAt"] = "not-an-instant"
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decidedAt")
    }

    @Test
    fun `fromMap rejects a reasons entry missing code naming the reasons index`() {
        val map = validRecordMap()
        @Suppress("UNCHECKED_CAST")
        map["reasons"] = listOf(mapOf("ruleId" to "rule-1"))
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reasons[0].code")
    }

    @Test
    fun `fromMap rejects a reasons entry that is not a map`() {
        val map = validRecordMap()
        map["reasons"] = listOf("not-a-map")
        assertThatThrownBy { DecisionRecord.fromMap(map) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reasons[0]")
    }

    @Test
    fun `fromMap accepts a legacy reasons entry with explicit null ruleId`() {
        val map = validRecordMap()
        map["reasons"] = listOf(mapOf("code" to "POLICY_DENY", "ruleId" to null))
        val decoded = DecisionRecord.fromMap(map)
        assertThat(decoded.reasons.single()).isEqualTo(DecisionReason(code = "POLICY_DENY", ruleId = null))
    }

    @Test
    fun `fromMap accepts legacy explicit-null correlation fields`() {
        val map = validRecordMap()
        map["traceId"] = null
        map["correlationId"] = null
        map["channel"] = null
        val decoded = DecisionRecord.fromMap(map)
        assertThat(decoded.correlation.traceId).isNull()
        assertThat(decoded.correlation.correlationId).isNull()
        assertThat(decoded.correlation.channel).isNull()
    }

    @Test
    fun `fromMap accepts absent correlation keys the same as explicit null`() {
        val map = validRecordMap()
        map.remove("traceId")
        map.remove("correlationId")
        map.remove("channel")
        val decoded = DecisionRecord.fromMap(map)
        assertThat(decoded.correlation.traceId).isNull()
        assertThat(decoded.correlation.correlationId).isNull()
        assertThat(decoded.correlation.channel).isNull()
    }

    @Test
    fun `toMap omits null-valued optional correlation and ruleId keys rather than writing an explicit null`() {
        val record = authzRecord().copy(
            reasons = listOf(DecisionReason(code = "POLICY_DENY", ruleId = null)),
            correlation = DecisionCorrelation(),
        )
        val map = with(DecisionRecord) { record.toMap() }

        assertThat(map).doesNotContainKeys("traceId", "correlationId", "channel")
        @Suppress("UNCHECKED_CAST")
        val reasonMap = (map["reasons"] as List<Map<String, Any?>>).single()
        assertThat(reasonMap).doesNotContainKey("ruleId")
    }

    // --- HumanReview.fromMap negatives ---------------------------------------------------------

    @Test
    fun `HumanReview fromMap rejects a missing type`() {
        assertThatThrownBy { HumanReview.fromMap(emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("type")
    }

    @Test
    fun `HumanReview fromMap rejects Completed missing by`() {
        assertThatThrownBy { HumanReview.fromMap(mapOf("type" to "COMPLETED", "at" to decidedAt.toString())) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("by")
    }

    @Test
    fun `HumanReview fromMap rejects Completed missing at`() {
        assertThatThrownBy { HumanReview.fromMap(mapOf("type" to "COMPLETED", "by" to "operator-1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at")
    }

    @Test
    fun `HumanReview fromMap rejects a malformed Completed at`() {
        assertThatThrownBy {
            HumanReview.fromMap(mapOf("type" to "COMPLETED", "by" to "operator-1", "at" to "not-an-instant"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at")
    }

    // --- DecisionOutcome.fromMap negatives ------------------------------------------------------

    @Test
    fun `DecisionOutcome fromMap rejects a missing kind`() {
        assertThatThrownBy { DecisionOutcome.fromMap(mapOf("name" to "ALLOW")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("kind")
    }

    @Test
    fun `DecisionOutcome fromMap rejects a missing name`() {
        assertThatThrownBy { DecisionOutcome.fromMap(mapOf("kind" to "AUTHZ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("name")
    }

    @Test
    fun `DecisionOutcome fromMap rejects a name that does not belong to the kind's subtype`() {
        assertThatThrownBy { DecisionOutcome.fromMap(mapOf("kind" to "AUTHZ", "name" to "PASS")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("name")
            .hasMessageContaining("PASS")
    }

    // --- round-trip variants (review finding: exercise the shapes fromMap must tolerate) --------

    @Test
    fun `round-trips a record with HumanReview None`() {
        val record = authzRecord().copy(humanReview = HumanReview.None)
        val decoded = DecisionRecord.fromMap(with(DecisionRecord) { record.toMap() })
        assertThat(decoded).isEqualTo(record)
    }

    @Test
    fun `round-trips a record with HumanReview Pending`() {
        val record = authzRecord().copy(humanReview = HumanReview.Pending)
        val decoded = DecisionRecord.fromMap(with(DecisionRecord) { record.toMap() })
        assertThat(decoded).isEqualTo(record)
    }

    @Test
    fun `round-trips a record with empty reasons and empty actChain`() {
        val record = authzRecord().copy(
            reasons = emptyList(),
            correlation = DecisionCorrelation(actChain = emptyList()),
        )
        val decoded = DecisionRecord.fromMap(with(DecisionRecord) { record.toMap() })
        assertThat(decoded).isEqualTo(record)
        assertThat(decoded.reasons).isEmpty()
        assertThat(decoded.correlation.actChain).isEmpty()
    }

    @Test
    fun `round-trips a record with all-null optional correlation fields`() {
        val record = authzRecord().copy(correlation = DecisionCorrelation())
        val decoded = DecisionRecord.fromMap(with(DecisionRecord) { record.toMap() })
        assertThat(decoded).isEqualTo(record)
    }

    @Test
    fun `round-trips atomic true and false`() {
        val atomicTrue = authzRecord().copy(atomic = true)
        val atomicFalse = authzRecord().copy(atomic = false)

        assertThat(DecisionRecord.fromMap(with(DecisionRecord) { atomicTrue.toMap() }).atomic).isTrue()
        assertThat(DecisionRecord.fromMap(with(DecisionRecord) { atomicFalse.toMap() }).atomic).isFalse()
    }

    @Test
    fun `round-trips a nano-precision Instant without losing precision`() {
        val nanoPrecise = Instant.parse("2026-09-26T10:00:00.123456789Z")
        val record = authzRecord().copy(
            decidedAt = nanoPrecise,
            humanReview = HumanReview.Completed(by = "operator-1", at = nanoPrecise),
        )

        val decoded = DecisionRecord.fromMap(with(DecisionRecord) { record.toMap() })

        assertThat(decoded.decidedAt).isEqualTo(nanoPrecise)
        assertThat((decoded.humanReview as HumanReview.Completed).at).isEqualTo(nanoPrecise)
    }
}
