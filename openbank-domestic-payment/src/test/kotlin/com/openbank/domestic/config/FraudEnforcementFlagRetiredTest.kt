// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `openbank.domestic.fraud.enforcement-enabled` was declared in `application.yaml`, documented in
 * ADR-0084 §4.2 and credited as a mitigation in the threat model, and **nothing read it** (#4221).
 * The code it named was deleted by the Temporal migration (#1917); the key outlived it silently,
 * because a config key with no reader fails by looking correct.
 *
 * This test is the thing that was missing: it asserts the general property (any
 * `openbank.domestic.*` config key is read by some Kotlin source) rather than the specific
 * absence, so it also covers the next key that loses its reader. A key that legitimately needs no
 * Kotlin reader — one consumed by a Quarkus extension — belongs in [FRAMEWORK_READ].
 */
class FraudEnforcementFlagRetiredTest {

    private val moduleRoot = File("").absoluteFile
    private val yaml = File(moduleRoot, "src/main/resources/application.yaml")
    private val kotlinSources = File(moduleRoot, "src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.readText() }
        .toList()

    @Test
    fun `the retired enforcement flag has not come back`() {
        assertThat(yamlKeysUnderOpenbankDomestic())
            .describedAs("re-adding this key needs a workflow-level decision path, not a boolean — see #4221")
            .doesNotContain("enforcement-enabled")
    }

    @Test
    fun `every openbank domestic config key is read by something`() {
        val unread = yamlKeysUnderOpenbankDomestic()
            .filterNot { it in FRAMEWORK_READ }
            .filterNot { key -> kotlinSources.any { it.contains(key) } }

        assertThat(unread)
            .describedAs(
                "these keys are declared but nothing in src/main/kotlin names them — either wire " +
                    "them, delete them, or list them in FRAMEWORK_READ with a reason",
            )
            .isEmpty()
    }

    /**
     * Leaf keys of the `openbank.domestic:` block. Deliberately textual and shallow: the point is
     * to notice a key nobody consumes, and a full SmallRye-faithful parse would not make that
     * judgement any sharper.
     */
    private fun yamlKeysUnderOpenbankDomestic(): List<String> {
        val lines = yaml.readLines()
        val start = lines.indexOfFirst { it.trimEnd() == "  domestic:" }
        check(start >= 0) { "the openbank.domestic block moved — this test needs updating, not deleting" }
        val indent = "    "
        return lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith(indent) || it.startsWith("#") }
            .filterNot { it.trimStart().startsWith("#") }
            .mapNotNull { line -> KEY.find(line)?.groupValues?.get(1) }
    }

    companion object {
        private val KEY = Regex("""^\s{4,}([a-z0-9-]+):\s*\S""")

        /** Keys consumed by a Quarkus extension or the YAML itself rather than by our Kotlin. */
        private val FRAMEWORK_READ = emptySet<String>()
    }
}
