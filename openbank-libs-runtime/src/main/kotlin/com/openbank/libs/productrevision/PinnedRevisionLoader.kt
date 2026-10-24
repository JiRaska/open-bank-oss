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

/**
 * A signed, published revision as a consumer keeps it locally: the IR JSON, the identity the
 * publish path signed (as the catalog reported it) and the detached signature over that identity.
 */
data class SignedRevisionCopy(val irJson: String, val identity: RevisionIdentity, val signature: IrSignature)

/**
 * Where a consumer keeps its local copies (ADR-0352 §6: product-catalog is a publication authority,
 * not a runtime dependency — this is a table, a file set or a config map the consumer owns, never
 * a live catalog call on the posting path). `null` means "no copy for this pin". The loader never
 * trusts the source to have matched the pin: it re-checks every field itself.
 */
fun interface SignedRevisionSource {
    fun fetch(pin: RevisionIdentity): SignedRevisionCopy?
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

    /**
     * The copy names another revision than the pin — a different product, revision id, revision
     * number or IR version — even though its hash and signature may be valid for THAT revision.
     */
    IDENTITY_MISMATCH,

    /** The signature is not a valid signature over the pinned identity's envelope under a trusted key. */
    SIGNATURE_INVALID,

    /** The verifier could not answer (OpenBao unreachable); not a verdict, and not a pass. */
    VERIFIER_UNAVAILABLE,
    ;

    val tag: String get() = name.lowercase()
}

/** Thrown for every outcome that is not [RevisionLoadOutcome.LOADED] / [RevisionLoadOutcome.CACHE_HIT]. */
class PinnedRevisionUnavailableException(
    val outcome: RevisionLoadOutcome,
    val pin: RevisionIdentity,
    cause: Throwable? = null,
) : RuntimeException(
    "pinned product revision ${pin.productCode}#${pin.revisionNumber} (${pin.revisionId}, " +
        "${pin.revisionHash}) unavailable: ${outcome.tag}",
    cause,
) {
    val revisionHash: String get() = pin.revisionHash
}

/**
 * Loads the pinned revision a contract names — a full [RevisionIdentity], never a bare hash — from
 * a local signed copy and **verifies the signature over that identity before returning it**
 * (ADR-0352 §4, §5, §6). Refuses an unsigned, mis-signed, mis-hashed, invalid or mis-identified
 * copy with [PinnedRevisionUnavailableException]; a consumer that catches it does not post.
 *
 * Binding by construction: the signature is verified against the PIN's envelope, not the copy's, so
 * a valid signature for another product or another revision of the same product cannot verify here;
 * the copy's claimed identity and the parsed IR's `productCode`/`irVersion` are additionally compared
 * with the pin first, so a substitution is reported as [RevisionLoadOutcome.IDENTITY_MISMATCH]
 * instead of as an anonymous signature failure.
 *
 * - The cache is keyed by the full identity and immutable: an identity names exactly one canonical
 *   document, so an entry can never go stale and is never evicted or replaced. Only a verified
 *   revision enters it.
 *   Its size is bounded by the number of distinct revisions the consumer's contracts pin (a handful
 *   per product, each a few KB), not by traffic: a request cannot add an entry the source does
 *   not already hold under a valid signature.
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
    private val cache = ConcurrentHashMap<RevisionIdentity, ProductRevisionIr>()

    /** The verified revision for [pin], or throws [PinnedRevisionUnavailableException]. */
    fun load(pin: RevisionIdentity): ProductRevisionIr {
        cache[pin]?.let {
            count(RevisionLoadOutcome.CACHE_HIT)
            return it
        }
        val copy = source.fetch(pin) ?: refuse(RevisionLoadOutcome.MISSING, pin)
        if (copy.identity != pin) refuse(RevisionLoadOutcome.IDENTITY_MISMATCH, pin)
        val revision = try {
            ProductIrParser.parse(copy.irJson)
        } catch (e: ProductIrValidationException) {
            refuse(RevisionLoadOutcome.INVALID_IR, pin, e)
        }
        if (revision.revisionHash != pin.revisionHash ||
            ProductIrCanonical.revisionHash(revision) != pin.revisionHash
        ) {
            refuse(RevisionLoadOutcome.HASH_MISMATCH, pin)
        }
        // The hash covers both fields, but a pin can still name a hash whose IR is another product.
        if (revision.productCode != pin.productCode || revision.irVersion != pin.irVersion) {
            refuse(RevisionLoadOutcome.IDENTITY_MISMATCH, pin)
        }
        // Verified against the PIN, never against what the copy claims: the binding holds by construction.
        val valid = try {
            verifier.verify(pin, copy.signature)
        } catch (e: IrSigningException) {
            refuse(RevisionLoadOutcome.VERIFIER_UNAVAILABLE, pin, e)
        }
        if (!valid) refuse(RevisionLoadOutcome.SIGNATURE_INVALID, pin)
        // putIfAbsent: two racing loaders of one pin verified the same bytes; either copy is the one.
        val cached = cache.putIfAbsent(pin, revision) ?: revision
        count(RevisionLoadOutcome.LOADED)
        return cached
    }

    /** Pins currently held; a consumer's health or debug surface, never a decision input. */
    fun cachedPins(): Set<RevisionIdentity> = cache.keys.toSet()

    private fun refuse(outcome: RevisionLoadOutcome, pin: RevisionIdentity, cause: Throwable? = null): Nothing {
        count(outcome)
        throw PinnedRevisionUnavailableException(outcome, pin, cause)
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
