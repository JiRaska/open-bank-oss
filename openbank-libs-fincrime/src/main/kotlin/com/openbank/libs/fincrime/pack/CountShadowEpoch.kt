// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.governance.Proposal
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.UUID

/** Identifies the exact comparison independently of who approves it or where it is stored. */
data class CountShadowReplayEvidence(
    val baseline: PackRef,
    val candidate: PackRef,
    val digest: String,
    val evaluatedCases: Int,
    val changedCases: Int,
) {
    init {
        packHash(digest)
        require(evaluatedCases in 0..CountPackReplay.MAX_CASES && changedCases in 0..CountPackReplay.MAX_CASES) {
            "shadow evidence counts exceed the bounded corpus"
        }
    }
}

/** Canonical evidence from the same interpreter used for shadow evaluation. */
object CountShadowReplay {
    fun evidence(
        baseline: CompiledCountPack,
        candidate: CompiledCountPack,
        cases: List<CountReplayCase>,
    ): CountShadowReplayEvidence {
        val corpus = cases.take(CountPackReplay.MAX_CASES + 1).map { case ->
            case.copy(observations = case.observations.take(CompiledCountPack.MAX_RULES + 1))
        }.sortedBy { it.id }
        val differences = CountPackReplay.compare(baseline, candidate, corpus)
        val records = corpus.zip(differences).map { (case, difference) ->
            mapOf(
                "case" to case.id,
                "inputs" to CountPackCanonical.inputHash(case.at, case.observations.sortedBy { it.reference.id }),
                "baseline" to difference.baseline.evidence(),
                "candidate" to difference.candidate.evidence(),
                "changed" to difference.behaviorChanged,
            )
        }
        val evidence = CanonicalJson.write(
            mapOf(
                "kind" to "openbank.fincrime.count-replay.v1",
                "baseline" to baseline.reference.fields(),
                "candidate" to candidate.reference.fields(),
                "records" to records,
            ),
        )
        return CountShadowReplayEvidence(
            baseline.reference,
            candidate.reference,
            AuditChain.sha256Hex(evidence),
            differences.count { it.baseline is CountEvaluation.Evaluated && it.candidate is CountEvaluation.Evaluated },
            differences.count { it.behaviorChanged },
        )
    }

    private fun CountEvaluation.evidence(): Map<String, Any?> = when (this) {
        is CountEvaluation.Incomplete -> mapOf("failure" to failure.name, "feature" to featureId)
        is CountEvaluation.Evaluated -> mapOf(
            "inputHash" to inputHash,
            "evaluatedRules" to evaluatedRules,
            "findings" to findings.map {
                mapOf(
                    "rule" to it.ruleId,
                    "reason" to it.reason.value,
                    "effect" to CountPackCanonical.effect(it.effect),
                    "reasonVocabulary" to CountPackCanonical.vocabulary(it.reasonVocabulary),
                    "severityVocabulary" to it.severityVocabulary?.let(CountPackCanonical::vocabulary),
                )
            },
        )
    }
}

/** Fields the trusted publisher signs after independent review of this exact replay. */
data class CountShadowEpoch(
    val number: Long,
    val effectiveAt: Instant,
    val signerKeyId: String,
    val proposalId: UUID,
    val proposedBy: UUID,
    val proposedAt: Instant,
    val approvedBy: UUID,
    val approvedAt: Instant,
    val replay: CountShadowReplayEvidence,
) {
    init {
        packIdentifier(signerKeyId)
    }

    fun signingInput(): ByteArray = CanonicalJson.write(
        mapOf(
            "kind" to "openbank.fincrime.count-shadow-epoch.v1",
            "epoch" to number.toString(),
            "effectiveAt" to effectiveAt,
            "signerKeyId" to signerKeyId,
            "proposalId" to proposalId,
            "proposedBy" to proposedBy,
            "proposedAt" to proposedAt,
            "approvedBy" to approvedBy,
            "approvedAt" to approvedAt,
            "baseline" to replay.baseline.fields(),
            "candidate" to replay.candidate.fields(),
            "evidence" to replay.digest,
            "evaluatedCases" to replay.evaluatedCases,
            "changedCases" to replay.changedCases,
        ),
    ).toByteArray(Charsets.UTF_8)
}

/** An observation tied to one verified SHADOW epoch, including explicit incomplete evaluations. */
data class CountShadowEvaluation(
    val epoch: Long,
    val pack: PackRef,
    val replayDigest: String,
    val result: CountEvaluation,
)

/** Immutable request pin. No live activation, remote lookup, threshold write or enforcement. */
class PinnedCountShadowEpoch private constructor(
    private val epoch: CountShadowEpoch,
    private val pack: CompiledCountPack,
) {
    fun evaluate(at: Instant, observations: List<CountObservation>): CountShadowEvaluation {
        require(at >= epoch.effectiveAt) { "decision precedes the pinned shadow epoch" }
        return CountShadowEvaluation(
            epoch.number,
            pack.reference,
            epoch.replay.digest,
            pack.evaluate(pack.reference, at, observations),
        )
    }

    companion object {
        /**
         * [trustedKeys] and [approvalIsCurrent] come from publisher-independent trust configuration.
         * The approval check compares the proposal, exact replay and current reviewer authority.
         * The caller supplies replay recomputed from the approved corpus. Persistence must compare
         * and set the epoch separately; verification creates only an immutable request pin.
         */
        fun verify(
            epoch: CountShadowEpoch,
            signature: ByteArray,
            pack: CompiledCountPack,
            replay: CountShadowReplayEvidence,
            trustedKeys: Map<String, PublicKey>,
            at: Instant,
            approvalIsCurrent: (CountShadowEpoch, PackDomain) -> Boolean,
        ): PinnedCountShadowEpoch {
            require(epoch.number > 0 && epoch.effectiveAt <= at) { "invalid shadow epoch" }
            require(epoch.replay == replay && replay.candidate == pack.reference && replay.evaluatedCases > 0) {
                "shadow epoch requires the exact nonzero replay evidence for this pack"
            }
            require(epoch.proposedAt <= epoch.approvedAt && epoch.approvedAt <= epoch.effectiveAt) {
                "shadow epoch cannot precede its independent approval"
            }
            val key = requireNotNull(trustedKeys[epoch.signerKeyId]) { "unknown shadow signer key" }
            require(verifySignature(key, epoch.signingInput(), signature)) { "invalid shadow epoch signature" }
            Proposal(epoch.proposalId.toString(), epoch.replay, epoch.proposedBy.toString(), epoch.proposedAt)
                .approve(epoch.approvedBy.toString(), epoch.approvedAt)
            require(approvalIsCurrent(epoch, pack.domain)) { "exact shadow approval is unavailable or unauthorized" }
            return PinnedCountShadowEpoch(epoch, pack)
        }

        private fun verifySignature(key: PublicKey, input: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != ED25519_SIGNATURE_BYTES) return false
            return try {
                Signature.getInstance("Ed25519").run {
                    initVerify(key)
                    update(input)
                    verify(signature)
                }
            } catch (_: GeneralSecurityException) {
                false
            }
        }

        private const val ED25519_SIGNATURE_BYTES = 64
    }
}

private fun PackRef.fields(): Map<String, Any> = mapOf("id" to id, "revision" to revision, "hash" to contentHash)
