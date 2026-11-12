// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MonitoringNameIndexTest {

    @Test
    fun `the size classes cover every trigram count once, up to the longest stored name`() {
        val covered = MonitoringNameIndex.CLASSES.flatMap {
            MonitoringNameIndex.smallest(it)..MonitoringNameIndex.largest(it)
        }
        assertThat(covered).isEqualTo((1..MonitoringNameIndex.MAX_TRIGRAMS).toList())
    }

    /**
     * The whole argument in numbers. For every size class, every name size in it, every entry
     * size and every count of shared trigrams that could still score the threshold, the name's
     * plain similarity to the entry is at or above the probe threshold, and so is the share of
     * the entry's trigrams found in the name, which is what the GIN index tests.
     */
    @Test
    fun `no name that can reach the score falls below its class's probe threshold`() {
        for (threshold in listOf(0.05, 0.3, 0.65, 0.85, 1.0)) {
            for (sizeClass in MonitoringNameIndex.CLASSES) {
                for (entry in listOf(1, 2, 7, 19, 60, 103, 400, 3000)) {
                    assertClassKeepsEveryReachableName(sizeClass, entry, threshold)
                }
            }
        }
    }

    private fun assertClassKeepsEveryReachableName(sizeClass: Int, entry: Int, threshold: Double) {
        val probe = MonitoringNameIndex.probeThreshold(sizeClass, entry, threshold)?.toDouble()
        val sizes = MonitoringNameIndex.smallest(sizeClass)..minOf(MonitoringNameIndex.largest(sizeClass), 400)
        if (probe == null) {
            assertThat(sizes.minOf { MonitoringNameIndex.minShared(it, threshold) })
                .describedAs("an unprobed class holds no name the entry could match")
                .isGreaterThan(entry)
            return
        }
        assertThat(probe).isBetween(0.0, 1.0)
        for (name in sizes) {
            for (shared in MonitoringNameIndex.minShared(name, threshold)..minOf(name, entry)) {
                val similarity = shared.toFloat() / (name + entry - shared).toFloat()
                assertThat(similarity.toDouble())
                    .describedAs("t=$threshold class=$sizeClass q=$entry k=$name shared=$shared")
                    .isGreaterThanOrEqualTo(probe)
                assertThat((shared.toFloat() / entry.toFloat()).toDouble()).isGreaterThanOrEqualTo(probe)
            }
        }
    }

    @Test
    fun `the fewest shared trigrams never round above what the score needs`() {
        // word_similarity is at most shared / k, so shared >= threshold * k is necessary.
        for (k in 1..600) {
            for (threshold in listOf(0.3, 0.65, 0.85)) {
                val needed = MonitoringNameIndex.minShared(k, threshold)
                assertThat(needed).isBetween(1, k)
                if (needed > 1) assertThat((needed - 1).toFloat() / k.toFloat()).isLessThan(threshold.toFloat())
            }
        }
    }

    @Test
    fun `a probe is positive and never asks for more than the class's exact floor`() {
        val probe = requireNotNull(MonitoringNameIndex.probeThreshold(6, 103, 0.65))
        // class 6 is 14..19 trigrams: 10 shared of the smallest, over 19 + 103 - 10.
        assertThat(probe.toDouble()).isGreaterThan(0.0).isLessThanOrEqualTo(10.0 / 112.0)
        assertThat(probe.scale()).isEqualTo(6)
        assertThat(MonitoringNameIndex.probeThreshold(15, 5, 0.65)).describedAs("unreachable class").isNull()
    }

    @Test
    fun `a threshold of zero or an entry without trigrams is rejected, not probed`() {
        assertThatThrownBy { MonitoringNameIndex.probeThreshold(1, 10, 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MonitoringNameIndex.probeThreshold(1, 0, 0.65) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
