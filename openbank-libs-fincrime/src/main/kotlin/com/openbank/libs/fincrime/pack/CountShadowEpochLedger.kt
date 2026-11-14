// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.CanonicalJson
import java.security.PublicKey
import java.time.Instant
import java.util.Base64

/** A serializable, content-addressed publisher envelope. */
data class SignedCountShadowEpoch(val epoch: CountShadowEpoch, val signature: String) {
    init {
        require(signature.length == ED25519_BASE64_LENGTH && SIGNATURE.matches(signature)) {
            "invalid encoded shadow publisher signature"
        }
        require(Base64.getEncoder().encodeToString(signatureBytes()) == signature) {
            "shadow publisher signature must use canonical base64"
        }
    }

    fun signatureBytes(): ByteArray = Base64.getDecoder().decode(signature)

    val digest: String get() = AuditChain.sha256Hex(
        CanonicalJson.write(
            mapOf(
                "kind" to "openbank.fincrime.signed-count-shadow-epoch.v1",
                "epoch" to Base64.getEncoder().encodeToString(epoch.signingInput()),
                "signature" to signature,
            ),
        ),
    )

    private companion object {
        const val ED25519_BASE64_LENGTH = 88
        val SIGNATURE = Regex("[A-Za-z0-9+/]{86}==")
    }
}

data class RecordedCountShadowEpoch(
    val domain: PackDomain,
    val previousNumber: Long?,
    val envelope: SignedCountShadowEpoch,
    val recordedAt: Instant,
) {
    init {
        val epoch = envelope.epoch
        require(epoch.number == Math.incrementExact(previousNumber ?: 0)) { "invalid shadow epoch sequence" }
        require(epoch.proposedBy != epoch.approvedBy && epoch.replay.evaluatedCases > 0) {
            "shadow ledger requires independent approval and nonzero evidence"
        }
        val approvedBeforeRecord = epoch.proposedAt <= epoch.approvedAt && epoch.approvedAt <= recordedAt
        require(approvedBeforeRecord && recordedAt <= epoch.effectiveAt) {
            "shadow ledger must record publication after approval and before effect"
        }
    }
}

enum class CountShadowAppendOutcome { APPENDED, REPLAYED, CONFLICT }

/** A durable per-domain append ledger. Implementations must check the expected head atomically. */
interface CountShadowEpochStore {
    suspend fun latest(domain: PackDomain): RecordedCountShadowEpoch?

    suspend fun recorded(domain: PackDomain, number: Long): RecordedCountShadowEpoch?

    suspend fun effectiveAt(domain: PackDomain, at: Instant): RecordedCountShadowEpoch?

    /** A retry may replay the exact envelope. A proposal ID cannot publish a different epoch. */
    suspend fun append(record: RecordedCountShadowEpoch): CountShadowAppendOutcome
}

/** Source artifacts and current independent approvals are resolved outside the publisher. */
interface CountShadowTrust {
    fun keys(): Map<String, PublicKey>

    suspend fun compiledPack(domain: PackDomain, reference: PackRef): CompiledCountPack?

    suspend fun approvedReplay(epoch: CountShadowEpoch, domain: PackDomain): CountShadowReplayEvidence?
}

/** Publishes and loads SHADOW pins without reaching the service's live decisions. */
class CountShadowEpochLedger(private val store: CountShadowEpochStore, private val trust: CountShadowTrust) {
    suspend fun publish(
        domain: PackDomain,
        signed: SignedCountShadowEpoch,
        expectedHead: Long?,
        at: Instant,
    ): CountShadowAppendOutcome {
        val epoch = signed.epoch
        val recorded = store.recorded(domain, epoch.number)
        if (recorded != null) {
            require(recorded.domain == domain) { "the shadow store returned another domain" }
            return if (recorded.envelope ==
                signed
            ) {
                CountShadowAppendOutcome.REPLAYED
            } else {
                CountShadowAppendOutcome.CONFLICT
            }
        }
        require(epoch.approvedAt <= at && epoch.effectiveAt >= at) { "a shadow publication cannot be backdated" }
        verified(domain, signed, epoch.effectiveAt)
        val previous = store.latest(domain)
        if (previous != null) require(previous.domain == domain) { "the shadow store returned another domain" }
        if (previous?.envelope?.epoch?.number != expectedHead) return CountShadowAppendOutcome.CONFLICT
        require(epoch.number == Math.incrementExact(expectedHead ?: 0)) { "shadow epochs must advance exactly once" }
        if (previous != null) {
            val last = previous.envelope.epoch
            require(epoch.effectiveAt > last.effectiveAt && epoch.replay.baseline == last.replay.candidate) {
                "the next shadow epoch must advance time and start from the current artifact"
            }
        }
        return store.append(RecordedCountShadowEpoch(domain, expectedHead, signed, at))
    }

    suspend fun pin(domain: PackDomain, at: Instant): PinnedCountShadowEpoch? {
        val current = store.effectiveAt(domain, at) ?: return null
        require(current.domain == domain && current.envelope.epoch.effectiveAt <= at) {
            "the shadow store returned an unrelated or future epoch"
        }
        return verified(domain, current.envelope, at)
    }

    private suspend fun verified(
        domain: PackDomain,
        signed: SignedCountShadowEpoch,
        at: Instant,
    ): PinnedCountShadowEpoch {
        val epoch = signed.epoch
        val pack = requireNotNull(trust.compiledPack(domain, epoch.replay.candidate)) {
            "the exact approved shadow pack is unavailable"
        }
        require(pack.domain == domain) { "the shadow pack belongs to another domain" }
        val replay = requireNotNull(trust.approvedReplay(epoch, domain)) {
            "the exact shadow proposal approval is unavailable"
        }
        return PinnedCountShadowEpoch.verify(epoch, signed.signatureBytes(), pack, replay, trust.keys(), at) {
                approved,
                approvedDomain,
            ->
            approved == epoch && approvedDomain == domain
        }
    }
}
