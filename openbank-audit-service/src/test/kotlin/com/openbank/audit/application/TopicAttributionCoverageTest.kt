// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [TopicAttribution]'s VALUES are hand-verified facts; its COVERAGE is derived from the config
 * (#3994).
 *
 * This repo has been bitten repeatedly by a gate whose scope is a hand-kept list of the thing it
 * checks: a short list reads as full coverage rather than as unchecked. A hand-kept table of
 * external facts is fine — it can only go stale by being noticed — but only if something fails
 * when it does. This is that something.
 *
 * Both directions are asserted on purpose. A missing entry means a newly subscribed topic silently
 * defaults to `"unknown"`, which is the original defect returning. A surplus entry means the table
 * still claims a topic nobody consumes, which is how a stale fact survives long enough to be
 * trusted.
 */
class TopicAttributionCoverageTest {

    @Test
    fun `every subscribed topic has a verified producer, and every mapped topic is subscribed`() {
        val subscribed = subscribedTopics()

        // Guard the probe itself: if the parse silently returned nothing, both assertions below
        // would pass while checking nothing at all.
        assertThat(subscribed)
            .describedAs("topics parsed out of application.yaml's audit-events-in channel")
            .hasSizeGreaterThan(15)

        assertThat(TopicAttribution.mappedTopics)
            .describedAs("TopicAttribution must name a producer for every topic audit-service consumes")
            .containsExactlyInAnyOrderElementsOf(subscribed)
    }

    /**
     * The `topics:` line of the `audit-events-in` incoming channel, read from the real config —
     * never a second copy of the list, which would move with the first and keep passing.
     */
    private fun subscribedTopics(): Set<String> {
        val yaml = File("src/main/resources/application.yaml").readText()
        val line = yaml.lineSequence()
            .map { it.trim() }
            .first { it.startsWith("topics:") && it.contains("openbank.") }
        return line.removePrefix("topics:").trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
