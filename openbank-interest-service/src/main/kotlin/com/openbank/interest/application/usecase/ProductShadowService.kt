// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.usecase

import com.openbank.interest.application.port.out.CatalogRevisionIrPort
import com.openbank.interest.application.port.out.CatalogRevisionUnavailableException
import com.openbank.interest.application.port.out.ProductShadowQueuePort
import com.openbank.interest.application.port.out.QueuedShadowInput
import com.openbank.interest.application.port.out.RevisionIrCopyPort
import com.openbank.interest.application.port.out.ShadowCompletion
import com.openbank.interest.application.port.out.ShadowLegacySourcePort
import com.openbank.interest.application.port.out.StoredRevisionIr
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.PinnedRevisionLoader
import com.openbank.libs.productrevision.PinnedRevisionUnavailableException
import com.openbank.libs.productrevision.RevisionLoadOutcome
import com.openbank.libs.productrevision.SignedRevisionCopy
import com.openbank.libs.productrevision.shadow.ProductShadowRecorder
import com.openbank.libs.productrevision.shadow.ShadowNotComparable
import io.micrometer.core.instrument.MeterRegistry
import org.jboss.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/** The collaborators [ProductShadowService] reads from; grouped so the constructor stays under detekt's limit. */
data class ProductShadowSources(
    val queue: ProductShadowQueuePort,
    val legacy: ShadowLegacySourcePort,
    val copies: RevisionIrCopyPort,
    val catalog: CatalogRevisionIrPort,
)

/**
 * The interest shadow comparator (ADR-0352 §6, §10, phase 3): enqueues committed accruals and
 * capitalisations, re-decides each with the product IR of the account's pinned revision, and records
 * agreement per `ruleId` through the shared [ProductShadowRecorder]. It decides nothing and posts
 * nothing: `InterestService` stays the only decision path until divergence has been zero for a full
 * cadence of every rule (one month, for monthly capitalisation, ADR-0352 §9) and a flag flips.
 *
 * Trust: the IR is used only after [PinnedRevisionLoader] has verified the publish-path signature over
 * its canonical hash. With verification switched off ([verifier] null) nothing is compared, and that is
 * counted as its own reason, never as agreement.
 */
class ProductShadowService(
    private val sources: ProductShadowSources,
    verifier: IrSignatureVerifierPort?,
    private val comparator: InterestShadowComparator,
    private val recorder: ProductShadowRecorder,
    meters: MeterRegistry? = null,
) {
    private val verifiedSource = ConcurrentHashMap<String, SignedRevisionCopy>()
    private val loader = verifier?.let { PinnedRevisionLoader({ hash -> verifiedSource[hash] }, it, meters) }
    private val log = Logger.getLogger(ProductShadowService::class.java)

    /** Enqueues newly committed legacy rows; each is counted as a queued input. */
    suspend fun intake(lookbackDays: Long, limit: Int): Int {
        val n = sources.queue.intake(lookbackDays, limit)
        repeat(n) { recorder.inputQueued() }
        return n
    }

    /** Processes one batch; returns how many inputs were claimed. */
    suspend fun runOnce(limit: Int): Int {
        val batch = sources.queue.claim(limit)
        for (item in batch) {
            when (val outcome = process(item)) {
                is Outcome.Done -> sources.queue.complete(item.id, outcome.completion)
                is Outcome.Retry -> if (item.attempts + 1 >= MAX_ATTEMPTS) {
                    sources.queue.complete(item.id, ShadowCompletion("${outcome.label}_EXHAUSTED"))
                } else {
                    sources.queue.retryLater(item.id, outcome.label)
                }
            }
        }
        return batch.size
    }

    private sealed interface Outcome {
        data class Done(val completion: ShadowCompletion) : Outcome
        data class Retry(val label: String) : Outcome
    }

    private suspend fun process(item: QueuedShadowInput): Outcome {
        val legacy = sources.legacy.load(item.kind, item.sourceId)
            ?: return Outcome.Done(ShadowCompletion("SOURCE_MISSING"))
        // No pin: an operator-managed rate no revision governs, so there is no IR to compare with.
        val revisionId = legacy.productRevisionId
            ?: return notComparable(ShadowNotComparable.NO_IR, label = "NO_PIN")
        val stored = localCopy(revisionId)
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
            comparator.compare(revision, legacy)
        } catch (e: ProductEvaluationException) {
            log.warnf("product shadow: IR of %s not evaluable: %s", revision.productCode, e.message)
            return notComparable(ShadowNotComparable.IR_EVALUATION_FAILED)
        }
        return Outcome.Done(record(revision.productCode, results))
    }

    private suspend fun localCopy(revisionId: String): StoredRevisionIr? = sources.copies.find(revisionId) ?: try {
        sources.copies.store(sources.catalog.fetch(revisionId))
    } catch (e: CatalogRevisionUnavailableException) {
        log.warnf("product shadow: revision %s unavailable (%s); retried later", revisionId, e.message)
        null
    }

    /** Counts every result; the queue row keeps the compared amounts (the first divergence, if any). */
    private fun record(productCode: String, results: List<InterestShadowResult>): ShadowCompletion {
        val compared = results.filterIsInstance<InterestShadowResult.Compared>()
        compared.forEach { recorder.compared(productCode, it.ruleId, it.diverged) }
        results.filterIsInstance<InterestShadowResult.Unmapped>()
            .forEach { _ -> recorder.notComparable(ShadowNotComparable.RULE_UNMAPPED) }
        val shown = compared.firstOrNull { it.diverged } ?: compared.firstOrNull()
        val outcome = when {
            compared.any { it.diverged } -> "DIVERGED"
            results.any { it is InterestShadowResult.Unmapped } -> "RULE_UNMAPPED"
            else -> "AGREED"
        }
        return ShadowCompletion(outcome, shown?.ruleId, shown?.legacyAmount, shown?.irAmount)
    }

    private fun trustedHash(stored: StoredRevisionIr): String? {
        val hash = stored.revisionHash ?: return null
        val json = stored.irJson ?: return null
        val signature = stored.signature ?: return null
        verifiedSource.putIfAbsent(hash, SignedRevisionCopy(json, signature))
        return hash
    }

    private fun notComparable(reason: ShadowNotComparable, retry: Boolean = false, label: String = reason.name): Outcome {
        recorder.notComparable(reason)
        return if (retry) Outcome.Retry(label) else Outcome.Done(ShadowCompletion(label))
    }

    companion object {
        const val MAX_ATTEMPTS = 12
    }
}
