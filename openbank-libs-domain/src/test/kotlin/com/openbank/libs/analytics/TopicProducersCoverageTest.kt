// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [TopicProducers]' VALUES are hand-verified facts; its COVERAGE is derived from the configs of the
 * services that read it.
 *
 * This repo has been bitten repeatedly by a gate whose scope is a hand-kept list of the thing it
 * checks: a short list reads as full coverage rather than as unchecked. A hand-kept table of
 * external facts is fine — it can only go stale by being noticed — but only if something fails when
 * it does.
 *
 * audit-service keeps the "no missing entry" half against its own subscription. This is the other
 * half, and it had to move here when the table became shared (#8792): a surplus entry means the
 * table claims a topic nobody consumes, and "nobody" is only answerable by a test that can see
 * BOTH consumers' configs. Asserting it inside either service would fail on every row that exists
 * for the other one, which is not staleness.
 *
 * Reading across the tree is deliberate, and the same reason the Customer 360 route pins the DDL it
 * depends on: the dependency between this table and those two `application.yaml` files has no
 * compiler behind it.
 */
class TopicProducersCoverageTest {

    private val consumers = listOf(
        "../openbank-audit-service/src/main/resources/application.yaml",
        "../openbank-analytics-sink/src/main/resources/application.yaml",
    )

    private fun subscribedTopics(path: String): Set<String> {
        val f = File(path)
        // A missing file must not read as "this consumer subscribes to nothing" — that would make
        // every row it justifies look surplus, and the assertion below would fail loudly for the
        // wrong reason. Failing here says which file, which is the difference between a diagnosis
        // and a puzzle.
        assertTrue(f.isFile, "consumer config not found: ${f.absolutePath}")
        val line = f.readText().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("topics:") && it.contains("openbank.") }
        assertTrue(line != null, "no `topics:` line naming openbank topics in $path")
        return line!!.removePrefix("topics:").trim()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    @Test
    fun `every mapped topic is consumed by at least one reader of this table`() {
        val consumed = consumers.flatMap { subscribedTopics(it) }.toSet()

        // Guard the probe itself: had both parses returned nothing, the assertion would report
        // every row as surplus — loudly, but about the wrong thing.
        assertTrue(
            consumed.size > 15,
            "parsed only ${consumed.size} subscribed topics across ${consumers.size} consumers",
        )

        val surplus = TopicProducers.mappedTopics - consumed
        assertTrue(
            surplus.isEmpty(),
            "rows claiming a topic neither audit-service nor analytics-sink consumes: $surplus",
        )
    }

    @Test
    fun `every consumed topic has a producer, across both readers`() {
        val consumed = consumers.flatMap { subscribedTopics(it) }.toSet()
        val unattributed = consumed.filter { TopicProducers.sourceService(it) == null }
        assertTrue(
            unattributed.isEmpty(),
            "subscribed topics that would attribute to `unknown`: $unattributed",
        )
    }
}
