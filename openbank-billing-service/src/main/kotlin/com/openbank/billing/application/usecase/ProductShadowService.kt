// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.CatalogRevisionIrPort
import com.openbank.billing.application.port.out.CatalogRevisionUnavailableException
import com.openbank.billing.application.port.out.ProductShadowQueuePort
import com.openbank.billing.application.port.out.QueuedShadowInput
import com.openbank.billing.application.port.out.RevisionIrCopyPort
import com.openbank.billing.application.port.out.StoredRevisionIr
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.PinnedRevisionLoader
import com.openbank.libs.productrevision.PinnedRevisionUnavailableException
import com.openbank.libs.productrevision.RevisionLoadOutcome
import com.openbank.libs.productrevision.SignedRevisionCopy
import com.openbank.libs.productrevision.shadow.ProductShadowRecorder
import com.openbank.libs.productrevision.shadow.ShadowNotComparable
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The billing shadow comparator (ADR-0352 §6, phase 3): drains the queue of legacy fee decisions,
 * re-decides each with the product IR of the account's pinned revision, and records agreement per
 * `ruleId`. It decides nothing and posts nothing: billing's legacy path stays the only decision path
 * until the divergence counter has been zero for a full cadence (ADR-0352 §9) and a flag flips.
 *
 * Trust: the IR is used only after [PinnedRevisionLoader] has verified the publish-path signature over
 * its canonical hash. With verification switched off ([verifier] null) nothing is compared, and that is
 * counted as its own reason, never as agreement.
 */
class ProductShadowService(
    private val queue: ProductShadowQueuePort,
    private val copies: RevisionIrCopyPort,
    private val catalog: CatalogRevisionIrPort,
    verifier: IrSignatureVerifierPort?,
    private val comparator: FeeShadowComparator,
    private val recorder: ProductShadowRecorder,
    private val clock: Clock,
    meters: io.micrometer.core.instrument.MeterRegistry? = null,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    /** The loader's source: copies this service has read from its own table, by hash. */
    private val verifiedSource = ConcurrentHashMap<String, SignedRevisionCopy>()
    private val loader = verifier?.let { PinnedRevisionLoader({ hash -> verifiedSource[hash] }, it, meters) }
    private val log = org.jboss.logging.Logger.getLogger(ProductShadowService::class.java)

    /** Processes one batch; returns how many inputs were claimed. */
    suspend fun runOnce(limit: Int): Int {
        val batch = queue.claim(limit)
        for (item in batch) {
            when (val outcome = process(item)) {
                is Outcome.Done -> queue.complete(item.id, outcome.label)
                is Outcome.Retry -> if (item.attempts + 1 >= maxAttempts) {
                    queue.complete(item.id, "${outcome.label}_EXHAUSTED")
                } else {
                    queue.retryLater(item.id, outcome.label)
                }
            }
        }
        return batch.size
    }

    private sealed interface Outcome {
        val label: String

        data class Done(override val label: String) : Outcome
        data class Retry(override val label: String) : Outcome
    }

    private suspend fun process(item: QueuedShadowInput): Outcome {
        val input = item.input
        val stored = localCopy(input.productRevisionId)
            ?: return notComparable(ShadowNotComparable.REVISION_UNAVAILABLE, retry = true)
        if (!stored.hasIr) return notComparable(ShadowNotComparable.NO_IR)
        val loader = loader ?: return notComparable(ShadowNotComparable.VERIFICATION_DISABLED)
        val hash = trustedHash(stored) ?: return notComparable(ShadowNotComparable.VERIFICATION_FAILED)
        val revision = try {
            loader.load(hash)
        } catch (e: PinnedRevisionUnavailableException) {
            return if (e.outcome == RevisionLoadOutcome.VERIFIER_UNAVAILABLE) {
                notComparable(ShadowNotComparable.VERIFIER_UNAVAILABLE, retry = true)
            } else {
                notComparable(ShadowNotComparable.VERIFICATION_FAILED)
            }
        }
        val results = try {
            comparator.compare(revision, input, Instant.now(clock))
        } catch (e: ProductEvaluationException) {
            log.warnf("product shadow: IR of %s not evaluable: %s", revision.productCode, e.message)
            return notComparable(ShadowNotComparable.IR_EVALUATION_FAILED)
        }
        return Outcome.Done(record(revision.productCode, results))
    }

    /** Billing's local copy, fetched from the catalog and stored on first sight; null when the catalog cannot answer. */
    private suspend fun localCopy(revisionId: String): StoredRevisionIr? = copies.find(revisionId) ?: try {
        copies.store(catalog.fetch(revisionId))
    } catch (e: CatalogRevisionUnavailableException) {
        // Counted as revision_unavailable by the caller; the cause is named, never the payload.
        log.warnf("product shadow: revision %s unavailable (%s); retried later", revisionId, e.message)
        null
    }

    /** Counts every result and returns the input's overall outcome. */
    private fun record(productCode: String, results: List<FeeShadowResult>): String {
        var diverged = false
        var unmapped = false
        var unknownInput = false
        for (r in results) {
            when (r) {
                is FeeShadowResult.Compared -> {
                    recorder.compared(productCode, r.ruleId, r.diverged)
                    diverged = diverged || r.diverged
                }
                is FeeShadowResult.UnknownInput -> {
                    recorder.notComparable(ShadowNotComparable.INPUT_UNKNOWN)
                    unknownInput = true
                }
                is FeeShadowResult.Unmapped -> {
                    recorder.notComparable(ShadowNotComparable.RULE_UNMAPPED)
                    unmapped = true
                }
            }
        }
        return when {
            diverged -> "DIVERGED"
            unmapped -> "RULE_UNMAPPED"
            unknownInput -> "INPUT_UNKNOWN"
            else -> "AGREED"
        }
    }

    /** Registers the stored copy with the loader's source and returns its hash; null when it carries no signature. */
    private fun trustedHash(stored: StoredRevisionIr): String? {
        val hash = stored.revisionHash ?: return null
        val json = stored.irJson ?: return null
        val signature = stored.signature ?: return null
        verifiedSource.putIfAbsent(hash, SignedRevisionCopy(json, signature))
        return hash
    }

    private fun notComparable(reason: ShadowNotComparable, retry: Boolean = false): Outcome {
        recorder.notComparable(reason)
        return if (retry) Outcome.Retry(reason.name) else Outcome.Done(reason.name)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 12
    }
}
