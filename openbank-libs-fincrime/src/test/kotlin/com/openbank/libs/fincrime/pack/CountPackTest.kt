// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabulary
import com.openbank.libs.fincrime.vocabulary.VocabularyCode
import com.openbank.libs.fincrime.vocabulary.VocabularyEntry
import com.openbank.libs.fincrime.vocabulary.VocabularySource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant

class CountPackTest {
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val until = Instant.parse("2027-01-01T00:00:00Z")
    private val at = from.plusSeconds(100)
    private val feature = CountFeatureRef("velocity_txn_count_h1", 1, "a".repeat(64))
    private val reason = VocabularyCode("BURST")
    private val severity = VocabularyCode("EXPEDITED")
    private val reasons = vocabulary("fraud.reasons", "fraud", reason)
    private val amlReasons = vocabulary("aml.reasons", "aml", VocabularyCode("SYNTHETIC_PATTERN"))
    private val scale = vocabulary("aml.case-severity", "aml", severity, 7)
    private val rule = CountRule("velocity", feature, 60, 10, reason, CountEffect.FraudAssessment(FraudVerdict.REVIEW))

    private fun vocabulary(id: String, domain: String, code: VocabularyCode, rank: Int? = null) =
        FincrimeVocabulary.compile(
            VocabularySource(
                1, id, 1, domain, "synthetic", "fixture-v1", from, until,
                listOf(VocabularyEntry(code, "Synthetic", "Synthetic observation only", rank)),
            ),
        )

    private fun source() = CountPackSource(
        1, "fraud.count-shadow", 1, PackDomain.FRAUD, "synthetic-review", from, until,
        reasons.reference, null, listOf(rule),
    )

    private fun input(value: Long = 10) = CountObservation(feature, value, at.minusSeconds(1), at)
    private fun compile(data: CountPackSource = source()) = CompiledCountPack.compile(data, reasons)

    @Test
    fun `synthetic fraud uses exact boundary counts and nonzero evaluation evidence`() {
        val pack = compile()
        val result = pack.evaluate(pack.reference, at, listOf(input())) as CountEvaluation.Evaluated
        assertThat(result.evaluatedRules).isEqualTo(1)
        assertThat(
            result.findings,
        ).containsExactly(CountFinding("velocity", reason, rule.effect, reasons.reference, null))
        assertThat(result.pack).isEqualTo(pack.reference)
        assertThat(result.at).isEqualTo(at)
        assertThat(result.inputHash).matches("[0-9a-f]{64}")
        val below = pack.evaluate(pack.reference, at, listOf(input(9))) as CountEvaluation.Evaluated
        assertThat(below.findings).isEmpty()
        assertThat(below.inputHash).isNotEqualTo(result.inputHash)
        assertThat(pack.evaluate(pack.reference, at, listOf(input()))).isEqualTo(result)
        assertThatThrownBy { (result.findings as MutableList<CountFinding>).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `AML scenario uses data severity and another vocabulary without a production taxonomy`() {
        val amlRule = rule.copy(reason = VocabularyCode("SYNTHETIC_PATTERN"), effect = CountEffect.AmlTriage(severity))
        val data = source().copy(
            id = "aml.synthetic",
            domain = PackDomain.AML,
            reasons = amlReasons.reference,
            severities = scale.reference,
            rules = listOf(amlRule),
        )
        val pack = CompiledCountPack.compile(data, amlReasons, scale)
        val result = pack.evaluate(pack.reference, at, listOf(input())) as CountEvaluation.Evaluated
        assertThat(
            result.findings,
        ).containsExactly(
            CountFinding("velocity", amlRule.reason, amlRule.effect, amlReasons.reference, scale.reference),
        )
        assertThat(scale.domain).isEqualTo("aml")
        assertThat(scale.purpose).isEqualTo("synthetic")
        assertThat(pack.evaluate(pack.reference, at, emptyList()))
            .isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.MISSING_FEATURE, feature.id))
    }

    @Test
    fun `canonical source golden binds exact instruction and vocabulary pins`() {
        val pack = compile()
        val golden = checkNotNull(
            javaClass.getResource("/fincrime/fraud-count-pack.canonical.json"),
        ).readText().trimEnd()
        assertThat(pack.canonicalJson).isEqualTo(golden)
        val hash = checkNotNull(javaClass.getResource("/fincrime/fraud-count-pack.sha256")).readText().trim()
        assertThat(pack.reference.contentHash).isEqualTo(hash)
        val huge = compile(source().copy(rules = listOf(rule.copy(minimum = Long.MAX_VALUE))))
        assertThat(huge.canonicalJson).contains("\"minimum\":\"9223372036854775807\"")
        assertThat(
            (huge.evaluate(huge.reference, at, listOf(input(Long.MAX_VALUE))) as CountEvaluation.Evaluated).findings,
        )
            .hasSize(1)
    }

    @Test
    fun `source and observations are order independent and snapshots survive caller mutation`() {
        val second = rule.copy(id = "another", feature = feature.copy(id = "other"), minimum = 20)
        val rules = mutableListOf(rule, second)
        val first = compile(source().copy(rules = rules))
        val secondPack = compile(source().copy(rules = rules.reversed()))
        rules.clear()
        assertThat(first.reference).isEqualTo(secondPack.reference)
        val inputs = listOf(input(), input(20).copy(reference = second.feature))
        assertThat(first.evaluate(first.reference, at, inputs))
            .isEqualTo(secondPack.evaluate(secondPack.reference, at, inputs.reversed()))
        assertThat(
            (first.evaluate(first.reference, at, inputs) as CountEvaluation.Evaluated).evaluatedRules,
        ).isEqualTo(2)
    }

    @Test
    fun `validity and freshness boundaries are inclusive then exclusive with explicit clock`() {
        val pack = compile()
        for (time in listOf(from.minusNanos(1), until)) {
            assertThat(pack.evaluate(pack.reference, time, listOf(input())))
                .isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.OUTSIDE_VALIDITY))
        }
        assertThat(pack.evaluate(pack.reference, from, listOf(input().copy(observedAt = from, availableAt = from))))
            .isInstanceOf(CountEvaluation.Evaluated::class.java)
        assertThat(pack.evaluate(pack.reference, at, listOf(input().copy(observedAt = at.minusSeconds(60)))))
            .isInstanceOf(CountEvaluation.Evaluated::class.java)
        assertThat(
            pack.evaluate(pack.reference, at, listOf(input().copy(observedAt = at.minusSeconds(60).minusNanos(1)))),
        )
            .isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.STALE_FEATURE, feature.id))
        val longer = compile(source().copy(effectiveUntil = until.plusSeconds(100)))
        assertThat(longer.evaluate(longer.reference, until, listOf(input())))
            .isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.VOCABULARY_UNAVAILABLE))
    }

    @TestFactory
    fun `input problems never become no findings or ALLOW`(): List<DynamicTest> {
        val pack = compile()
        val cases = listOf(
            emptyList<CountObservation>() to
                CountEvaluation.Incomplete(CountEvaluationFailure.MISSING_FEATURE, feature.id),
            listOf(input().copy(reference = feature.copy(revision = 2))) to
                CountEvaluation.Incomplete(CountEvaluationFailure.FEATURE_REVISION_MISMATCH, feature.id),
            listOf(input().copy(availableAt = at.plusNanos(1))) to
                CountEvaluation.Incomplete(CountEvaluationFailure.FEATURE_UNAVAILABLE, feature.id),
            listOf(input(-1)) to CountEvaluation.Incomplete(CountEvaluationFailure.INVALID_INPUT),
            listOf(input().copy(observedAt = at.plusSeconds(1))) to
                CountEvaluation.Incomplete(CountEvaluationFailure.INVALID_INPUT),
            listOf(input(), input()) to CountEvaluation.Incomplete(CountEvaluationFailure.INVALID_INPUT),
            List(65) { input().copy(reference = feature.copy(id = "f$it")) } to
                CountEvaluation.Incomplete(CountEvaluationFailure.INVALID_INPUT),
        )
        return cases.mapIndexed { index, (inputs, expected) ->
            DynamicTest.dynamicTest("invalid inputs $index") {
                assertThat(pack.evaluate(pack.reference, at, inputs)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `wrong pack pin rejects before using inputs and missing second rule still makes whole result incomplete`() {
        val pack = compile()
        assertThat(pack.evaluate(pack.reference.copy(contentHash = "0".repeat(64)), at, listOf(input())))
            .isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.REVISION_MISMATCH))
        val two =
            compile(source().copy(rules = listOf(rule, rule.copy(id = "z", feature = feature.copy(id = "missing")))))
        assertThat(two.evaluate(two.reference, at, listOf(input())))
            .isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.MISSING_FEATURE, "missing"))
        val zero = pack.evaluate(pack.reference, at, listOf(input(0))) as CountEvaluation.Evaluated
        assertThat(zero.findings).isEmpty()
        assertThat(zero.evaluatedRules).isEqualTo(1)
    }

    @TestFactory
    fun `bad source never becomes executable`(): List<DynamicTest> {
        val data = source()
        val invalid = listOf(
            data.copy(schemaVersion = 2), data.copy(id = "bad id"), data.copy(purpose = ""), data.copy(revision = 0),
            data.copy(effectiveUntil = from), data.copy(reasons = reasons.reference.copy(revision = 2)),
            data.copy(severities = scale.reference), data.copy(rules = emptyList()),
            data.copy(
                rules = List(65) {
                    rule
                },
            ),
            data.copy(rules = listOf(rule, rule)), data.copy(rules = listOf(rule.copy(id = "bad id"))),
            data.copy(rules = listOf(rule.copy(minimum = -1))), data.copy(rules = listOf(rule.copy(maxAgeSeconds = 0))),
            data.copy(rules = listOf(rule.copy(maxAgeSeconds = 86401))),
            data.copy(rules = listOf(rule.copy(reason = VocabularyCode("MISSING")))),
            data.copy(rules = listOf(rule.copy(effect = CountEffect.AmlTriage(severity)))),
            data.copy(rules = listOf(rule.copy(effect = CountEffect.FraudAssessment(FraudVerdict.UNKNOWN)))),
            data.copy(rules = listOf(rule, rule.copy(id = "other", feature = feature.copy(revision = 2)))),
            data.copy(domain = PackDomain.AML),
        )
        return invalid.mapIndexed { index, source ->
            DynamicTest.dynamicTest("bad source $index") {
                assertThatThrownBy { compile(source) }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `AML cannot use an unordered or foreign scale or unknown severity`() {
        val aml = source().copy(
            domain = PackDomain.AML,
            reasons = amlReasons.reference,
            severities = scale.reference,
            rules = listOf(
                rule.copy(
                    reason = VocabularyCode("SYNTHETIC_PATTERN"),
                    effect = CountEffect.AmlTriage(VocabularyCode("MISSING")),
                ),
            ),
        )
        assertThatThrownBy {
            CompiledCountPack.compile(aml, amlReasons, scale)
        }.isInstanceOf(IllegalArgumentException::class.java)
        for (bad in listOf(vocabulary("bad", "aml", severity), vocabulary("bad", "kyc", severity, 1))) {
            assertThatThrownBy { CompiledCountPack.compile(aml.copy(severities = bad.reference), amlReasons, bad) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `bounds allow exactly 64 rules and exact hash identities are validated`() {
        val pack = compile(source().copy(rules = List(64) { rule.copy(id = "r$it") }))
        assertThat(
            (pack.evaluate(pack.reference, at, listOf(input())) as CountEvaluation.Evaluated).evaluatedRules,
        ).isEqualTo(64)
        assertThatThrownBy { feature.copy(id = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { feature.copy(revision = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            feature.copy(contentHash = "G".repeat(64))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { pack.reference.copy(revision = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `replay reports changed findings but not a metadata-only revision and counts real cases`() {
        val baseline = compile()
        val candidate = compile(source().copy(revision = 2, rules = listOf(rule.copy(minimum = 11))))
        val cases =
            listOf(
                CountReplayCase("boundary", at, listOf(input())),
                CountReplayCase("below", at, listOf(input(9))),
                CountReplayCase("missing", at, emptyList()),
            )
        val diff = CountPackReplay.compare(baseline, candidate, cases)
        assertThat(diff.map { it.behaviorChanged }).containsExactly(true, false, false)
        assertThat(diff.last().baselinePack).isEqualTo(baseline.reference)
        assertThat(diff.last().candidatePack).isEqualTo(candidate.reference)
        assertThat(diff.map { it.caseId }).containsExactly("boundary", "below", "missing")
        assertThat(
            CountPackReplay.compare(baseline, compile(source().copy(revision = 2)), cases).map {
                it.behaviorChanged
            },
        )
            .containsOnly(false)
        val extra = compile(source().copy(rules = listOf(rule.copy(feature = feature.copy(id = "missing")))))
        assertThat(CountPackReplay.compare(baseline, extra, cases).first().behaviorChanged).isTrue()
        assertThatThrownBy {
            (diff as MutableList<CountReplayDifference>).clear()
        }.isInstanceOf(UnsupportedOperationException::class.java)
        for (bad in listOf(
            emptyList(),
            listOf(cases[0], cases[0]),
            List(257) { cases[0].copy(id = "c$it") },
            listOf(cases[0].copy(id = "bad id")),
        )) {
            assertThatThrownBy {
                CountPackReplay.compare(baseline, candidate, bad)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `AML replay pins its own domain and changed severity cannot leak into fraud comparison`() {
        val amlRule = rule.copy(reason = VocabularyCode("SYNTHETIC_PATTERN"), effect = CountEffect.AmlTriage(severity))
        val data = source().copy(
            id = "aml.synthetic",
            domain = PackDomain.AML,
            reasons = amlReasons.reference,
            severities = scale.reference,
            rules = listOf(amlRule),
        )
        val baseline = CompiledCountPack.compile(data, amlReasons, scale)
        val candidate = CompiledCountPack.compile(
            data.copy(revision = 2, rules = listOf(amlRule.copy(minimum = 11))),
            amlReasons,
            scale,
        )
        val cases = listOf(CountReplayCase("aml-boundary", at, listOf(input())))
        assertThat(CountPackReplay.compare(baseline, candidate, cases).single().behaviorChanged).isTrue()
        assertThatThrownBy {
            CountPackReplay.compare(baseline, compile(), cases)
        }.isInstanceOf(IllegalArgumentException::class.java)
        for (changed in listOf(source().copy(id = "other"), source().copy(purpose = "other"))) {
            assertThatThrownBy {
                CountPackReplay.compare(compile(), compile(changed), cases)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @TestFactory
    fun `every changed instruction or metadata produces another artifact identity`(): List<DynamicTest> {
        val data = source()
        val variants = listOf(
            data.copy(id = "other"), data.copy(revision = 2), data.copy(purpose = "another-purpose"),
            data.copy(effectiveFrom = from.plusSeconds(1)), data.copy(effectiveUntil = until.plusSeconds(1)),
            data.copy(rules = listOf(rule.copy(id = "other"))), data.copy(rules = listOf(rule.copy(minimum = 11))),
            data.copy(rules = listOf(rule.copy(maxAgeSeconds = 61))),
            data.copy(rules = listOf(rule.copy(feature = feature.copy(revision = 2)))),
            data.copy(rules = listOf(rule.copy(feature = feature.copy(contentHash = "b".repeat(64))))),
            data.copy(rules = listOf(rule.copy(effect = CountEffect.FraudAssessment(FraudVerdict.DECLINE)))),
        )
        return variants.mapIndexed { index, changed ->
            DynamicTest.dynamicTest("changed identity $index") {
                assertThat(compile(changed).reference.contentHash).isNotEqualTo(compile().reference.contentHash)
            }
        }
    }

    @Test
    fun `a changed meaning of the same reason code is a visible behavioral difference`() {
        val changedReasons = FincrimeVocabulary.compile(
            VocabularySource(
                1, "fraud.reasons", 2, "fraud", "synthetic", "fixture-v2", from, until,
                listOf(VocabularyEntry(reason, "Synthetic", "Changed policy meaning")),
            ),
        )
        val changed = CompiledCountPack.compile(
            source().copy(revision = 2, reasons = changedReasons.reference),
            changedReasons,
        )
        val diff = CountPackReplay.compare(compile(), changed, listOf(CountReplayCase("meaning", at, listOf(input()))))
        assertThat(diff.single().behaviorChanged).isTrue()
    }
}
