// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

/**
 * Maps an ISO-18245 merchant category code to the category a customer can block or cap.
 *
 * The bank owns this mapping, deliberately. If a client hardcoded "gambling is MCC 7995", adding
 * a code to the gambling set would need an app release, and every customer on an older build would
 * keep spending against a block they believe is on. Served over the API so the enforcement point
 * and the display agree by construction.
 *
 * Codes with no mapping fall into [UNMAPPED]. That category is deliberately **not** blockable:
 * blocking "everything I haven't classified" would decline arbitrary legitimate spend as the
 * acquirer estate changes, and the customer could never learn what they had turned off.
 */
object MerchantCategoryTaxonomy {
    /** Where an unrecognised MCC lands. Limitable — you may cap it — but never blockable. */
    const val UNMAPPED: String = "OTHER"

    data class Category(
        val id: String,
        val label: String,
        val mccRanges: List<String>,
        val blockable: Boolean,
        val limitable: Boolean,
    )

    /**
     * `7800-7802` style ranges are inclusive on both ends; a bare code is a range of one. Kept as
     * strings because that is the shape the API publishes, and one source avoids the two drifting.
     */
    private data class Range(val from: Int, val to: Int)

    val CATEGORIES: List<Category> = listOf(
        // Gambling is the reason the whole feature exists: it is the one category where a customer
        // asking to be stopped is asking for protection, not budgeting.
        Category("GAMBLING", "Hazard", listOf("7800-7802", "7995", "9754"), blockable = true, limitable = true),
        Category("GROCERIES", "Potraviny", listOf("5411", "5422", "5441", "5451", "5462", "5499"), true, true),
        Category("DINING", "Restaurace", listOf("5811-5814"), true, true),
        Category("TRANSPORT", "Doprava", listOf("4111-4131", "4121", "5541-5542", "7523"), true, true),
        Category("TRAVEL", "Cestování", listOf("3000-3999", "4511-4722", "7011"), true, true),
        Category("SHOPPING", "Nákupy", listOf("5200-5399", "5611-5699", "5712-5722", "5940-5999"), true, true),
        Category("ENTERTAINMENT", "Zábava", listOf("7832-7841", "7911-7999"), true, true),
        Category("HEALTH", "Zdraví", listOf("5912", "5975-5977", "8011-8099"), true, true),
        Category("SERVICES", "Služby", listOf("4812-4816", "4899-4900", "7210-7299"), true, true),
        Category("CASH", "Výběry hotovosti", listOf("6010-6012"), true, true),
        // Unmapped: limitable, never blockable — see the class KDoc.
        Category(UNMAPPED, "Ostatní", emptyList(), blockable = false, limitable = true),
    )

    private val RANGES: Map<String, List<Range>> = CATEGORIES.associate { c ->
        c.id to c.mccRanges.map { spec ->
            val parts = spec.split("-")
            Range(parts.first().toInt(), parts.last().toInt())
        }
    }

    private val BY_ID: Map<String, Category> = CATEGORIES.associateBy { it.id }

    /**
     * The category for [mcc], or [UNMAPPED] when the code is unknown, malformed or absent.
     *
     * Never throws: an authorisation decision must not fail because an acquirer sent a code we do
     * not recognise. An unrecognised code becomes OTHER and is judged on OTHER's rules.
     */
    fun categoryOf(mcc: String?): String {
        val code = mcc?.trim()?.toIntOrNull() ?: return UNMAPPED
        return CATEGORIES.firstOrNull { c ->
            RANGES[c.id].orEmpty().any { code >= it.from && code <= it.to }
        }?.id ?: UNMAPPED
    }

    fun isKnown(categoryId: String): Boolean = categoryId in BY_ID

    fun isBlockable(categoryId: String): Boolean = BY_ID[categoryId]?.blockable ?: false
}
