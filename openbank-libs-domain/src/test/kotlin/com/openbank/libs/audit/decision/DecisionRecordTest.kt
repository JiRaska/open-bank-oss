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

    private fun authzRecord(
        outcome: DecisionOutcome = DecisionOutcome.Authz.ALLOW,
        decisionClass: DecisionClass = DecisionClass.AUTHZ,
    ) = DecisionRecord(
        decisionClass = decisionClass,
        decidedAt = decidedAt,
        inputDigest = digest(),
        engine = engine(),
        outcome = outcome,
        subjectRef = SubjectRef("account-1"),
        atomic = false,
        retentionClass = DecisionRetentionClass.AUDIT_LOG_5Y,
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
}
