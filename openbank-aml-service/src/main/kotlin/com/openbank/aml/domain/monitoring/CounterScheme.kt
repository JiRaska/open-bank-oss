// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.monitoring

import java.math.BigDecimal
import java.time.Instant

/**
 * A named amount band in ONE currency: `lowerInclusive <= amount < upperExclusive`.
 *
 * Bands are data (ADR-0371 §2). No band boundary, threshold or window length is written in Kotlin.
 */
data class AmountBand(
    val code: String,
    val currency: String,
    val lowerInclusive: BigDecimal,
    val upperExclusive: BigDecimal,
) {
    init {
        require(code.matches(CODE) && code != CounterScheme.ALL_BAND) { "invalid band code" }
        require(currency.matches(CURRENCY)) { "band currency is three upper-case letters" }
        require(lowerInclusive.signum() >= 0 && upperExclusive > lowerInclusive) { "band bounds are not ordered" }
    }

    fun contains(currency: String, amount: BigDecimal): Boolean =
        currency == this.currency && amount >= lowerInclusive && amount < upperExclusive

    private companion object {
        val CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        val CURRENCY = Regex("[A-Z]{3}")
    }
}

/**
 * How booked movements are bucketed into counters: the bucket width and the amount bands.
 *
 * Every movement increments the [ALL_BAND] counter of its bucket plus one counter per band that
 * contains it. Bands never span currencies, so a count can never mix them. Counters carry
 * [revision], so a changed band boundary starts new counters instead of silently redefining the
 * meaning of existing ones.
 */
data class CounterScheme(
    val id: String,
    val revision: Int,
    val contentHash: String,
    val bucketSeconds: Long,
    val bands: List<AmountBand>,
) {
    init {
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) { "invalid scheme id" }
        require(revision > 0) { "scheme revision is positive" }
        require(contentHash.matches(Regex("[0-9a-f]{64}"))) { "scheme hash is a SHA-256" }
        require(bucketSeconds in MIN_BUCKET_SECONDS..SECONDS_PER_DAY && SECONDS_PER_DAY % bucketSeconds == 0L) {
            "bucket width must divide a day"
        }
        require(bands.size <= MAX_BANDS) { "too many bands" }
        require(bands.map { it.code }.distinct().size == bands.size) { "band codes are unique" }
    }

    /** Band codes this movement counts under; always includes [ALL_BAND]. */
    fun bandCodesFor(currency: String, amount: BigDecimal): List<String> =
        listOf(ALL_BAND) + bands.filter { it.contains(currency, amount) }.map { it.code }

    /** Start of the bucket containing [at], aligned to the epoch. */
    fun bucketStart(at: Instant): Instant = Instant.ofEpochSecond(
        Math.floorDiv(at.epochSecond, bucketSeconds) * bucketSeconds,
    )

    companion object {
        /** Reserved band code of the band-independent total. */
        const val ALL_BAND = "ALL"
        const val MAX_BANDS = 64
        const val MIN_BUCKET_SECONDS = 60L
        const val SECONDS_PER_DAY = 86_400L
    }
}
