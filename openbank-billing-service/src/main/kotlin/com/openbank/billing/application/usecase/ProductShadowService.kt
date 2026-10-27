// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.CatalogRevisionIrPort
import com.openbank.billing.application.port.out.CatalogRevisionMismatchException
import com.openbank.billing.application.port.out.CatalogRevisionUnavailableException
import com.openbank.billing.application.port.out.ProductShadowQueuePort
import com.openbank.billing.application.port.out.QueuedShadowInput
import com.openbank.billing.application.port.out.RevisionIrCopyPort
import com.openbank.billing.application.port.out.StoredRevisionIr
import com.openbank.billing.domain.ProductShadowInput
import com.openbank.billing.domain.ShadowRevisionIdentity
import com.openbank.libs.product.ir.ProductEvaluationException
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.PinnedRevisionLoader
import com.openbank.libs.productrevision.PinnedRevisionUnavailableException
import com.openbank.libs.productrevision.RevisionIdentity
import com.openbank.libs.productrevision.RevisionLoadOutcome
import com.openbank.libs.productrevision.SignedRevisionCopy
import com.openbank.libs.productrevision.shadow.ProductShadowRecorder
import com.openbank.libs.productrevision.shadow.ShadowNotComparable
import io.micrometer.core.instrument.Counter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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
    private val meters: io.micrometer.core.instrument.MeterRegistry? = null,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    /** The loader's source: copies this service has read from its own table, by hash. */
    private val verifiedSource = ConcurrentHashMap<RevisionIdentity, SignedRevisionCopy>()
    private val loader = verifier?.let { PinnedRevisionLoader({ hash -> verifiedSource[hash] }, it, meters) }
    private val log = org.jboss.logging.Logger.getLogger(ProductShadowService::class.java)

    /** Processes one batch; returns how many inputs were claimed. */
    suspend fun runOnce(limit: Int): Int {
        val batch = queue.claim(limit)
        for (item in batch) {
            when (val outcome = processGuarded(item)) {
                is Outcome.Done -> queue.complete(item.id, outcome.label, outcome.compared)
                is Outcome.Retry -> if (item.attempts + 1 >= maxAttempts) {
                    queue.complete(item.id, "${outcome.label}_EXHAUSTED", compared = false)
                } else {
                    queue.retryLater(item.id, outcome.label)
                }
            }
        }
        return batch.size
    }

    private sealed interface Outcome {
        val label: String

        /** [compared] is true when the IR re-decided the input, so its frozen facts are no longer needed. */
        data class Done(override val label: String, val compared: Boolean = false) : Outcome
        data class Retry(override val label: String) : Outcome
    }

    /**
     * One item's failure is that item's outcome, never the batch's: an unexpected exception (an NPE or a
     * DateTimeException from the interpreter, an unmappable row) completes the item as
     * IR_EVALUATION_FAILED instead of escaping, which would leave it to be re-claimed ahead of every other
     * row on each run. Cancellation and JVM errors still propagate.
     */
    private suspend fun processGuarded(item: QueuedShadowInput): Outcome = runCatching { process(item) }
        .getOrElse { e ->
            if (e is CancellationException || e is Error) throw e
            log.warnf("product shadow: input %s not evaluable (%s)", item.id, e.javaClass.simpleName)
            notComparable(ShadowNotComparable.IR_EVALUATION_FAILED)
        }

    private suspend fun process(item: QueuedShadowInput): Outcome {
        val input = item.input ?: return notComparable(ShadowNotComparable.IR_EVALUATION_FAILED)
        val expected = input.expectedRevision
            ?: return notComparable(ShadowNotComparable.INPUT_UNKNOWN)
        val pin = capturedPin(input) ?: return notComparable(ShadowNotComparable.INPUT_UNKNOWN)
        val stored = try {
            localCopy(expected)
        } catch (e: CatalogRevisionMismatchException) {
            log.warnf("product shadow: %s; copy not stored", e.message)
            return notComparable(ShadowNotComparable.VERIFICATION_FAILED)
        } ?: return notComparable(ShadowNotComparable.REVISION_UNAVAILABLE, retry = true)
        if (!stored.hasIr) {
            copies.store(stored)
            return notComparable(ShadowNotComparable.NO_IR)
        }
        val loader = loader ?: return notComparable(ShadowNotComparable.VERIFICATION_DISABLED)
        if (!trustedCopy(stored, expected)) return notComparable(ShadowNotComparable.VERIFICATION_FAILED)
        val revision = try {
            loader.load(pin)
        } catch (e: PinnedRevisionUnavailableException) {
            return if (e.outcome == RevisionLoadOutcome.VERIFIER_UNAVAILABLE) {
                notComparable(ShadowNotComparable.VERIFIER_UNAVAILABLE, retry = true)
            } else {
                notComparable(ShadowNotComparable.VERIFICATION_FAILED)
            }
        }
        // Verify the full signed pin before inserting or enriching an immutable local copy.
        if (copies.store(stored) != stored) return notComparable(ShadowNotComparable.VERIFICATION_FAILED)
        val results = try {
            comparator.compare(revision, input)
        } catch (e: ProductEvaluationException) {
            log.warnf("product shadow: IR of %s not evaluable: %s", revision.productCode, e.message)
            return notComparable(ShadowNotComparable.IR_EVALUATION_FAILED)
        }
        // The facts are shared by every fee: a partial comparison must preserve what unresolved
        // fees still need. An empty result did not re-decide any fee either.
        val fullyCompared = results.isNotEmpty() && results.all { it is FeeShadowResult.Compared }
        return Outcome.Done(record(revision.productCode, results), compared = fullyCompared)
    }

    /** Billing's local copy, fetched from the catalog and stored on first sight; null when the catalog cannot answer. */
    private suspend fun localCopy(expected: ShadowRevisionIdentity): StoredRevisionIr? = try {
        val existing = copies.find(expected.revisionId)
        if (existing != null && existing.identity != null) return existing
        val fetched = catalog.fetch(expected.revisionId)
        if (fetched.hasIr && fetched.identity != expected) {
            throw CatalogRevisionMismatchException("catalog identity differs from the frozen assessment pin")
        }
        fetched
    } catch (e: CatalogRevisionUnavailableException) {
        // Counted as revision_unavailable by the caller; the cause is named, never the payload.
        log.warnf("product shadow: revision %s unavailable (%s); retried later", expected.revisionId, e.message)
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
            results.isEmpty() -> {
                // Neither side has a fee: nothing was compared, which must not read as agreement.
                meters?.let {
                    Counter.builder(NOTHING_TO_COMPARE)
                        .description("Shadow inputs with no fee on either side, so nothing compared (ADR-0352)")
                        .register(it).increment()
                }
                NO_FEES
            }
            else -> "AGREED"
        }
    }

    /** Only an independently captured complete pin can authorize a shadow comparison. */
    private fun capturedPin(input: ProductShadowInput): RevisionIdentity? {
        val expected = input.expectedRevision ?: return null
        if (expected.revisionId != input.productRevisionId) return null
        return runCatching { expected.pin() }.getOrNull()
    }

    private fun trustedCopy(stored: StoredRevisionIr, expected: ShadowRevisionIdentity): Boolean {
        if (stored.identity != expected ||
            stored.revisionId != expected.revisionId ||
            stored.revisionHash != expected.revisionHash
        ) {
            return false
        }
        val json = stored.irJson ?: return false
        val signature = stored.signature ?: return false
        verifiedSource.putIfAbsent(expected.pin(), SignedRevisionCopy(json, expected.pin(), signature))
        return true
    }

    private fun ShadowRevisionIdentity.pin() = RevisionIdentity(
        productCode,
        UUID.fromString(revisionId),
        revisionNumber,
        irVersion,
        revisionHash,
    )

    private fun notComparable(reason: ShadowNotComparable, retry: Boolean = false): Outcome {
        recorder.notComparable(reason)
        return if (retry) Outcome.Retry(reason.name) else Outcome.Done(reason.name)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 12
        const val NO_FEES = "NO_FEES"
        const val NOTHING_TO_COMPARE = "openbank_billing_product_shadow_nothing_to_compare_total"
    }
}
