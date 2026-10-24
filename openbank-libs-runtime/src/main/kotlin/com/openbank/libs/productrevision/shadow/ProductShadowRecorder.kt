// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision.shadow

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

/**
 * Why a legacy decision could not be compared with the product IR (ADR-0352 §6). Each is its own
 * value of `openbank_product_shadow_not_comparable_total{reason}`, never folded into "compared":
 * a shadow that compared nothing must read as exactly that, not as agreement (ADR-0252 phase 0).
 */
enum class ShadowNotComparable {
    /** The pinned revision carries no product IR: the product has not been migrated. Expected today. */
    NO_IR,

    /** The signed copy could not be fetched (catalog down); the input stays queued and is retried. */
    REVISION_UNAVAILABLE,

    /** Signature verification is switched off for this consumer, so the IR is not trusted. */
    VERIFICATION_DISABLED,

    /** The copy failed verification (bad signature, hash mismatch, invalid IR): a defect to investigate. */
    VERIFICATION_FAILED,

    /** The verifier could not answer (OpenBao unreachable); retried, never a pass. */
    VERIFIER_UNAVAILABLE,

    /** A legacy decision has no counterpart rule in the IR (or the reverse): the mapping is incomplete. */
    RULE_UNMAPPED,

    /** A required comparison input was not captured; no economic decision can be compared. */
    INPUT_UNKNOWN,

    /**
     * The interpreter refused to evaluate (a run limit), or produced a `Post` that is not balanced per
     * currency. An interpreter defect, never a ledger error to discover later (ADR-0352 §8).
     */
    IR_EVALUATION_FAILED,
    ;

    val tag: String get() = name.lowercase()
}

/**
 * The shadow comparator's metrics, shared by every phase-3 consumer (billing, interest, lending,
 * account) so the alert rules read ONE family of series (ADR-0352 §6):
 *
 * - `openbank_product_shadow_inputs_total{consumer}` — legacy decisions queued for comparison;
 * - `openbank_product_shadow_compared_total{consumer,product,rule_id}` — decisions compared;
 * - `openbank_product_shadow_diverged_total{consumer,product,rule_id}` — of those, the ones where the
 *   IR decided differently (a subset of compared, never a separate population);
 * - `openbank_product_shadow_not_comparable_total{consumer,reason}` — decisions that could not be
 *   compared, by [ShadowNotComparable].
 *
 * Cardinality is bounded by construction: `product` is a product code and `rule_id` an IR rule id
 * declared in a revision (at most a few dozen per product); no account, contract or amount is ever
 * a tag (threat model: the comparator must not become an information channel).
 *
 * A plain class, not a CDI bean (ADR-0320 rule 1): a consumer constructs one with its registry.
 * A `null` registry disables metrics and changes nothing else.
 */
class ProductShadowRecorder(private val registry: MeterRegistry?, private val consumer: String) {

    init {
        require(CONSUMER.matches(consumer)) { "consumer must be a lower-case service short name" }
    }

    fun inputQueued() {
        val reg = registry ?: return
        Counter.builder(INPUTS).tag(TAG_CONSUMER, consumer)
            .description(
                "Legacy decisions queued for comparison with the product IR (ADR-0352)",
            ).register(reg).increment()
    }

    /** One legacy decision compared with the IR rule [ruleId] of product [productCode]. */
    fun compared(productCode: String, ruleId: String, diverged: Boolean) {
        val reg = registry ?: return
        Counter.builder(COMPARED).tag(TAG_CONSUMER, consumer).tag(TAG_PRODUCT, productCode).tag(TAG_RULE, ruleId)
            .description("Legacy decisions compared with the product IR (ADR-0352)").register(reg).increment()
        if (diverged) {
            Counter.builder(DIVERGED).tag(TAG_CONSUMER, consumer).tag(TAG_PRODUCT, productCode).tag(TAG_RULE, ruleId)
                .description("Compared decisions where the product IR decided differently (ADR-0352)")
                .register(reg).increment()
        }
    }

    fun notComparable(reason: ShadowNotComparable) {
        val reg = registry ?: return
        Counter.builder(NOT_COMPARABLE).tag(TAG_CONSUMER, consumer).tag("reason", reason.tag)
            .description("Legacy decisions the shadow could not compare, by reason (ADR-0352)")
            .register(reg).increment()
    }

    companion object {
        const val INPUTS = "openbank_product_shadow_inputs_total"
        const val COMPARED = "openbank_product_shadow_compared_total"
        const val DIVERGED = "openbank_product_shadow_diverged_total"
        const val NOT_COMPARABLE = "openbank_product_shadow_not_comparable_total"
        private const val TAG_CONSUMER = "consumer"
        private const val TAG_PRODUCT = "product"
        private const val TAG_RULE = "rule_id"
        private val CONSUMER = Regex("^[a-z][a-z0-9-]{1,40}$")
    }
}
