// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

/**
 * Text → vector, through the same governed gateway every chat call uses (ADR-0174 / ADR-0175,
 * ADR-0183 §3). Embedding calls are LLM calls: routing them anywhere else would put an un-audited
 * egress next to the audited one, which is the exact posture the gateway exists to prevent.
 *
 * Pure domain, no framework imports. `null` means "no embeddings available" — unconfigured,
 * unreachable, or a response that did not parse. As everywhere else in this package, the failure
 * value is distinct from a legitimate result: an empty list is a valid answer to an empty request,
 * so callers must not use `isEmpty()` to detect an outage.
 *
 * [dimensions] is declared rather than inferred because the database column is fixed-width
 * (`vector(N)`): a model swap that changes the width invalidates every stored row, and the mismatch
 * has to be detectable before an INSERT fails one row at a time.
 */
interface EmbeddingPort {

    /** The model id as sent upstream — stored next to every embedding so a model swap is detectable. */
    val model: String

    /** Vector width this model produces; must match the `vector(N)` column. */
    val dimensions: Int

    /**
     * Embed [texts] in one round trip, preserving order. Returns `null` when no embedding could be
     * obtained at all; never a partial list — a caller cannot tell which item a short list dropped.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>?

    companion object {
        /**
         * Produces nothing and says so. The default for a caller that has not been wired: retrieval
         * then degrades to keyword-only, which is a real (previously the only) mode — but the caller
         * must report that it did, or "search got worse" becomes unattributable.
         */
        val DISABLED: EmbeddingPort = object : EmbeddingPort {
            override val model: String = "none"
            override val dimensions: Int = 0
            override suspend fun embed(texts: List<String>): List<FloatArray>? = null
        }
    }
}
