// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductIrValidationException
import com.openbank.libs.product.ir.ProductRevisionIr
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

/** A signed, published revision as a consumer keeps it locally: the IR JSON and the detached signature. */
data class SignedRevisionCopy(val irJson: String, val signature: IrSignature)

/**
 * Where a consumer keeps its local copies (ADR-0352 §6: product-catalog is a publication authority,
 * not a runtime dependency — this is a table, a file set or a config map the consumer owns, never
 * a live catalog call on the posting path). `null` means "no copy under this hash".
 */
fun interface SignedRevisionSource {
    fun fetch(revisionHash: String): SignedRevisionCopy?
}

/**
 * One outcome per `load`, the `outcome` tag of [PinnedRevisionLoader.METRIC]. A verification failure
 * is its OWN value, never shared with success or with "missing" (the ADR-0252 phase 0 lesson).
 */
enum class RevisionLoadOutcome {
    /** Served from the immutable in-memory cache. */
    CACHE_HIT,

    /** Fetched, parsed, hash-checked, signature-verified and cached. */
    LOADED,

    /** The source holds no copy under that hash: fail closed on a missing pin (ADR-0352 §6). */
    MISSING,

    /** The copy does not parse as valid IR. */
    INVALID_IR,

    /** The copy parses, but its canonical hash is not the hash it was filed under. */
    HASH_MISMATCH,

    /** The signature is not a valid signature over the hash under a trusted key. */
    SIGNATURE_INVALID,

    /** The verifier could not answer (OpenBao unreachable); not a verdict, and not a pass. */
    VERIFIER_UNAVAILABLE,
    ;

    val tag: String get() = name.lowercase()
}

/** Thrown for every outcome that is not [RevisionLoadOutcome.LOADED] / [RevisionLoadOutcome.CACHE_HIT]. */
class PinnedRevisionUnavailableException(
    val outcome: RevisionLoadOutcome,
    val revisionHash: String,
    cause: Throwable? = null,
) : RuntimeException("pinned product revision $revisionHash unavailable: ${outcome.tag}", cause)

/**
 * Loads the pinned revision a contract names, by `revisionHash`, from a local signed copy and
 * **verifies the signature before returning it** (ADR-0352 §4, §5, §6). Refuses an unsigned,
 * mis-signed, mis-hashed or invalid copy with [PinnedRevisionUnavailableException]; a consumer
 * that catches it does not post.
 *
 * - The cache is keyed by hash and immutable: a hash names exactly one canonical document, so an
 *   entry can never go stale and is never evicted or replaced. Only a verified revision enters it.
 * - Every call increments [METRIC] with one [RevisionLoadOutcome]; a registry of `null` disables
 *   metrics (a bare unit test), nothing else changes.
 * - No consumer wires this yet (ADR-0352 phase 2); phase 3 consumers construct one from their own
 *   `src/main` with their own [SignedRevisionSource] and a verify-only [IrSignatureVerifierPort].
 */
class PinnedRevisionLoader(
    private val source: SignedRevisionSource,
    private val verifier: IrSignatureVerifierPort,
    private val registry: MeterRegistry? = null,
) {
    private val cache = ConcurrentHashMap<String, ProductRevisionIr>()

    /** The verified revision under [revisionHash], or throws [PinnedRevisionUnavailableException]. */
    fun load(revisionHash: String): ProductRevisionIr {
        cache[revisionHash]?.let {
            count(RevisionLoadOutcome.CACHE_HIT)
            return it
        }
        val copy = source.fetch(revisionHash) ?: refuse(RevisionLoadOutcome.MISSING, revisionHash)
        val revision = try {
            ProductIrParser.parse(copy.irJson)
        } catch (e: ProductIrValidationException) {
            refuse(RevisionLoadOutcome.INVALID_IR, revisionHash, e)
        }
        if (revision.revisionHash != revisionHash || ProductIrCanonical.revisionHash(revision) != revisionHash) {
            refuse(RevisionLoadOutcome.HASH_MISMATCH, revisionHash)
        }
        val valid = try {
            verifier.verify(revisionHash, copy.signature)
        } catch (e: IrSigningException) {
            refuse(RevisionLoadOutcome.VERIFIER_UNAVAILABLE, revisionHash, e)
        }
        if (!valid) refuse(RevisionLoadOutcome.SIGNATURE_INVALID, revisionHash)
        // putIfAbsent: two racing loaders of one hash verified the same bytes; either copy is the one.
        val cached = cache.putIfAbsent(revisionHash, revision) ?: revision
        count(RevisionLoadOutcome.LOADED)
        return cached
    }

    /** Hashes currently held; a consumer's health or debug surface, never a decision input. */
    fun cachedHashes(): Set<String> = cache.keys.toSet()

    private fun refuse(outcome: RevisionLoadOutcome, hash: String, cause: Throwable? = null): Nothing {
        count(outcome)
        throw PinnedRevisionUnavailableException(outcome, hash, cause)
    }

    private fun count(outcome: RevisionLoadOutcome) {
        val reg = registry ?: return
        Counter.builder(METRIC)
            .description(
                "Pinned product revision loads by outcome (ADR-0352); verification failures are their own outcome",
            )
            .tag("outcome", outcome.tag)
            .register(reg)
            .increment()
    }

    companion object {
        const val METRIC = "openbank_product_revision_load_total"
    }
}
