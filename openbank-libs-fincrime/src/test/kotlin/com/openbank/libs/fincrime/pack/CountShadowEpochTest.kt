// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.audit.decision.DecisionClass
import com.openbank.libs.audit.decision.DecisionOutcome
import com.openbank.libs.audit.decision.DecisionRecord
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabularyJson
import com.openbank.libs.governance.MakerCheckerViolation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID

class CountShadowEpochTest {
    private val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val maker = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val at = Instant.parse("2026-01-01T00:02:00Z")
    private val input = CountObservation(
        CountFeatureRef("velocity_txn_count_h1", 1, "a".repeat(64)),
        11,
        at.minusSeconds(1),
        at,
    )
    private val cases = listOf(
        CountReplayCase("changed", at, listOf(input.copy(value = 10))),
        CountReplayCase("finding", at, listOf(input)),
        CountReplayCase("missing", at, emptyList()),
    )

    @Test
    fun `AML and fraud both produce findings under their independently signed shadow pins`() {
        for (domain in PackDomain.entries) {
            val (baseline, candidate) = fixtures(domain)
            val replay = CountShadowReplay.evidence(baseline, candidate, cases)
            assertThat(replay.evaluatedCases).isEqualTo(2)
            assertThat(replay.changedCases).isEqualTo(1)
            assertThat(CountShadowReplay.evidence(baseline, candidate, cases.reversed())).isEqualTo(replay)

            val first = epoch(replay)
            val pinned = verify(first, candidate, replay)
            val evaluated = pinned.evaluate(at, listOf(input))
            assertThat(evaluated.epoch).isEqualTo(first.number)
            assertThat(evaluated.pack).isEqualTo(candidate.reference)
            assertThat(evaluated.replayDigest).isEqualTo(replay.digest)
            assertThat((evaluated.result as CountEvaluation.Evaluated).findings).hasSize(1)
            val missing = pinned.evaluate(at, emptyList())
            assertThat((missing.result as CountEvaluation.Incomplete).failure)
                .isEqualTo(CountEvaluationFailure.MISSING_FEATURE)
            assertThatIllegalArgumentException().isThrownBy { pinned.evaluate(at.minusNanos(1), listOf(input)) }

            val rollbackEvidence = CountShadowReplay.evidence(candidate, baseline, cases)
            val rollback = epoch(rollbackEvidence).copy(number = 2)
            val next = verify(rollback, baseline, rollbackEvidence)
            assertThat(next.evaluate(at, listOf(input)).pack).isEqualTo(baseline.reference)
            assertThat(pinned.evaluate(at, listOf(input))).isEqualTo(evaluated)
        }
    }

    @Test
    fun `approval and publisher trust are both checked against the exact shadow contents`() {
        val (baseline, candidate) = fixtures(PackDomain.FRAUD)
        val replay = CountShadowReplay.evidence(baseline, candidate, cases)
        val epoch = epoch(replay)
        val signature = sign(epoch)
        val changedCase = cases.first().copy(observations = listOf(input.copy(value = 9)))
        val stale = CountShadowReplay.evidence(baseline, candidate, listOf(changedCase))
        assertThat(stale.digest).isNotEqualTo(replay.digest)
        assertThatIllegalArgumentException().isThrownBy { verify(epoch, candidate, stale, signature) }
        assertThatIllegalArgumentException().isThrownBy { verify(epoch, baseline, replay, signature) }
        assertThatIllegalArgumentException().isThrownBy { verify(epoch.copy(number = 2), candidate, replay, signature) }
        assertThatIllegalArgumentException().isThrownBy { verify(epoch, candidate, replay, ByteArray(64)) }
        assertThatIllegalArgumentException().isThrownBy { verify(epoch, candidate, replay, ByteArray(1)) }
        assertThatIllegalArgumentException().isThrownBy { epoch.copy(signerKeyId = "invalid?publisher") }
        val unknownPublisher = epoch.copy(signerKeyId = "unknown")
        assertThatIllegalArgumentException().isThrownBy { verify(unknownPublisher, candidate, replay) }
        assertThatIllegalArgumentException().isThrownBy {
            PinnedCountShadowEpoch.verify(epoch, signature, candidate, replay, keys(), at) { _, _ -> false }
        }
        assertThatThrownBy { verify(epoch.copy(approvedBy = maker), candidate, replay) }
            .isInstanceOf(MakerCheckerViolation::class.java)
        val forged = epoch.copy(proposalId = UUID.randomUUID())
        assertThatIllegalArgumentException().isThrownBy {
            PinnedCountShadowEpoch.verify(forged, sign(forged), candidate, replay, keys(), at) { proposal, domain ->
                proposal.proposalId == epoch.proposalId && proposal.replay == replay && domain == candidate.domain
            }
        }
    }

    @Test
    fun `an empty evaluation or an epoch predating review cannot be pinned even when signed`() {
        val (baseline, candidate) = fixtures(PackDomain.AML)
        val missing = CountShadowReplay.evidence(baseline, candidate, listOf(cases.last()))
        assertThat(missing.evaluatedCases).isZero()
        assertThatIllegalArgumentException().isThrownBy { verify(epoch(missing), candidate, missing) }
        val replay = CountShadowReplay.evidence(baseline, candidate, cases)
        assertThatIllegalArgumentException().isThrownBy { replay.copy(evaluatedCases = CountPackReplay.MAX_CASES + 1) }
        val approved = epoch(replay)
        for (invalid in listOf(approved.copy(number = 0), approved.copy(approvedAt = at.plusSeconds(1)))) {
            assertThatIllegalArgumentException().isThrownBy { verify(invalid, candidate, replay) }
        }
    }

    @Test
    fun `oversized replay inputs cannot be approved using a digest of their prefix`() {
        val (baseline, candidate) = fixtures(PackDomain.AML)
        val oversized = CountReplayCase("oversized", at, List(CompiledCountPack.MAX_RULES + 1) { input })
        assertThatIllegalArgumentException().isThrownBy {
            CountShadowReplay.evidence(baseline, candidate, cases + oversized)
        }
        val result = candidate.evaluate(candidate.reference, at, oversized.observations)
        assertThat(result).isEqualTo(CountEvaluation.Incomplete(CountEvaluationFailure.INVALID_INPUT))
    }

    @Test
    fun `the existing DecisionRecord preserves exact shadow pins for both outcomes and domains`() {
        for (domain in PackDomain.entries) {
            val (baseline, candidate) = fixtures(domain)
            val replay = CountShadowReplay.evidence(baseline, candidate, cases)
            val pinned = verify(epoch(replay), candidate, replay)
            val evaluated = DecisionRecord.fromMap(
                pinned.decisionRecord(UUID.randomUUID(), "synthetic-subject", at, listOf(input), atomic = true),
            )
            val incomplete = DecisionRecord.fromMap(
                pinned.decisionRecord(UUID.randomUUID(), "synthetic-subject", at, emptyList(), atomic = false),
            )
            assertThat(evaluated.decisionClass).isEqualTo(DecisionClass.SHADOW)
            assertThat(evaluated.engine.id).isEqualTo(candidate.reference.id)
            assertThat(evaluated.engine.version).isEqualTo(candidate.reference.revision.toString())
            assertThat(evaluated.atomic).isTrue()
            assertThat(incomplete.atomic).isFalse()
            val first = evaluated.outcome as DecisionOutcome.Shadow
            val second = incomplete.outcome as DecisionOutcome.Shadow
            assertThat(first.result).isEqualTo(DecisionOutcome.Shadow.Result.EVALUATED)
            assertThat(second.result).isEqualTo(DecisionOutcome.Shadow.Result.INCOMPLETE)
            assertThat(first.artifactHash.hex).isEqualTo(candidate.reference.contentHash)
            assertThat(first.approvedEvidenceHash.hex).isEqualTo(replay.digest)
            assertThat(first.epoch).isEqualTo(1)
            assertThat(first.outputHash).isNotEqualTo(second.outputHash)
            assertThat(evaluated.inputDigest).isNotEqualTo(incomplete.inputDigest)
            val oversized = List(CompiledCountPack.MAX_RULES + 1) { input }
            assertThatIllegalArgumentException().isThrownBy {
                pinned.decisionRecord(UUID.randomUUID(), "synthetic-subject", at, oversized, atomic = false)
            }
        }
    }

    private fun fixtures(domain: PackDomain): Pair<CompiledCountPack, CompiledCountPack> {
        val aml = domain == PackDomain.AML
        val file = if (aml) "aml-count-source.json" else "fraud-count-pack.canonical.json"
        val reasons = FincrimeVocabularyJson.compile(fixture("${domain.name.lowercase()}-reasons-source.json"))
        val scale = if (aml) FincrimeVocabularyJson.compile(fixture("aml-severity-source.json")) else null
        val source = fixture(file)
        val variant = ObjectMapper().readTree(source) as ObjectNode
        variant.put("revision", 2)
        (variant["rules"][0] as ObjectNode).put("minimum", "11")
        val baseline = CountPackJson.compile(source, reasons, scale)
        val candidate = CountPackJson.compile(variant.toString(), reasons, scale)
        return baseline to candidate
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fincrime/$name")).readText()

    private fun epoch(replay: CountShadowReplayEvidence) = CountShadowEpoch(
        1, at, KEY_ID, UUID.randomUUID(), maker, at.minusSeconds(2), reviewer, at.minusSeconds(1), replay,
    )

    private fun verify(
        epoch: CountShadowEpoch,
        candidate: CompiledCountPack,
        replay: CountShadowReplayEvidence,
        signature: ByteArray = sign(epoch),
    ) = PinnedCountShadowEpoch.verify(epoch, signature, candidate, replay, keys(), at) { approval, domain ->
        approval.approvedBy == reviewer && approval.replay == replay && domain == candidate.domain
    }

    private fun keys() = mapOf(KEY_ID to signer.public)

    private fun sign(epoch: CountShadowEpoch): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(signer.private)
        update(epoch.signingInput())
        sign()
    }

    private companion object {
        const val KEY_ID = "synthetic-publisher"
    }
}
