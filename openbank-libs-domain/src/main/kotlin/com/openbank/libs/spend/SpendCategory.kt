// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.spend

/**
 * The bank's one spend-category vocabulary.
 *
 * These ids already existed, inside card-issuance's `MerchantCategoryTaxonomy`, where they are
 * bound to MCC ranges and to the blockable/limitable flags that card controls act on. They are
 * lifted here — ids only, no MCC and no flags — because a second consumer now needs the same
 * vocabulary: a customer categorising their own transactions, including the ones no card was
 * involved in.
 *
 * Ids only, deliberately. What a category *means for a card* (can it be blocked, can it carry a
 * limit, which MCCs fall in it) stays card-issuance's business. Duplicating the flags here would
 * create a second answer to a question that has an owner, and the wrong answer to "is GAMBLING
 * blockable" is an authorisation defect.
 *
 * `MerchantCategoryTaxonomyDriftTest` in card-issuance asserts that service's ids are exactly
 * [IDS]. That test is what makes this a shared vocabulary rather than a copy that will rot.
 *
 * The list is MCC-derived and so has no term for spend a card never sees — rent, salary, transfers
 * between people. Adding one is not a matter of appending a string here: card-issuance must gain
 * the same id, and each new id has to declare whether a card control may block or limit it. That
 * is a decision with authorisation consequences, so it gets its own change rather than riding along
 * with this one.
 */
object SpendCategory {
    /** The category assigned when nothing else matches. Never absent from [IDS]. */
    const val UNMAPPED: String = "OTHER"

    /**
     * Every category id the bank recognises, in the order card-issuance declares them.
     *
     * Order is part of the contract: the drift test compares sequences, so a reordering in either
     * place is a failure rather than a silent divergence in how the two services enumerate.
     */
    val IDS: List<String> = listOf(
        "GAMBLING",
        "GROCERIES",
        "DINING",
        "TRANSPORT",
        "TRAVEL",
        "SHOPPING",
        "ENTERTAINMENT",
        "HEALTH",
        "SERVICES",
        "CASH",
        UNMAPPED,
    )

    private val SET: Set<String> = IDS.toSet()

    /** True when [id] is a category this bank recognises. Case-sensitive: ids are stored uppercase. */
    fun isKnown(id: String?): Boolean = id != null && id in SET
}
