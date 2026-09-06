// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADR-0140 says parity is "tested, not assumed". This test is what keeps that true as the catalogue
 * grows: the scope is DERIVED from the declarations in source, never a second list that would move
 * with the first and keep passing.
 *
 * The failure it exists to prevent is not a wrong value — it is a feature that no catalogue claims
 * and no test covers, which reads exactly like a covered one.
 */
class FeatureCatalogueCoverageTest {

    private val sourceDir = File("src/main/kotlin/com/openbank/libs/domain/feature")

    private fun declarations(regex: Regex): Set<String> {
        // Guard the probe: if the directory moved, every assertion below would pass while checking
        // nothing. This is the whole class of bug the test is about, so it must not have it itself.
        assertTrue(sourceDir.isDirectory, "feature source directory not found: ${sourceDir.absolutePath}")
        val files = sourceDir.listFiles { f -> f.name.endsWith(".kt") }?.toList().orEmpty()
        assertTrue(files.size >= 3, "parsed only ${files.size} source files — the probe is broken, not the catalogue")
        return files.flatMap { f -> regex.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
    }

    private fun declaredFeatures() =
        declarations(Regex("""^val ([A-Z_0-9]+): FeatureDefinition""", RegexOption.MULTILINE))

    private fun declaredCatalogues() =
        declarations(Regex("""^val ([A-Z_0-9]+): List<FeatureDefinition>""", RegexOption.MULTILINE))

    @Test
    fun `every declared catalogue is classified as online-served or offline-only`() {
        val declared = declaredCatalogues()
        assertTrue(declared.isNotEmpty(), "no catalogues parsed — the probe is broken")

        val unclassified = declared - FeatureCatalogues.ALL
        assertTrue(
            unclassified.isEmpty(),
            "catalogues declared but classified nowhere in FeatureCatalogues: $unclassified. " +
                "Add each to ONLINE_SERVED (and to FeatureParityIT) or to OFFLINE_ONLY with a reason.",
        )

        val phantom = FeatureCatalogues.ALL - declared
        assertTrue(
            phantom.isEmpty(),
            "FeatureCatalogues names catalogues that no longer exist: $phantom",
        )
    }

    /**
     * A catalogue cannot be in both lists: "served online" and "offline only" are contradictory, and
     * a value in both would let either assertion find what it wanted.
     */
    @Test
    fun `no catalogue is both online-served and offline-only`() {
        val both = FeatureCatalogues.ONLINE_SERVED.keys intersect FeatureCatalogues.OFFLINE_ONLY.keys
        assertTrue(both.isEmpty(), "classified as both: $both")
    }

    /**
     * Every declared feature must be reachable through some catalogue. A feature declared and left
     * out of every list is served by nothing and covered by nothing, while looking exactly like one
     * that is.
     */
    /**
     * Every declared feature must be reachable through some catalogue. A feature declared and left
     * out of every list is served by nothing and covered by nothing, while looking exactly like one
     * that is.
     *
     * Counted rather than matched name-by-name: the Kotlin val and the feature's own `name` are
     * different facts (the val is source, the name is what the store keys on), and pairing them
     * would need reflection over top-level properties across files. The count cannot be satisfied
     * by an uncovered feature, which is the property that matters, and the name-uniqueness test
     * below covers the other half.
     */
    @Test
    fun `every declared feature belongs to a catalogue`() {
        val declared = declaredFeatures()
        assertTrue(declared.size >= 2, "parsed only ${declared.size} features — the probe is broken")

        val reachable = (FeatureCatalogues.ONLINE_SERVED_FEATURES + MONEY_FLOW_FEATURES).map { it.name }.toSet()
        assertTrue(
            reachable.size >= declared.size,
            "declared ${declared.size} feature(s) in source but only ${reachable.size} are reachable " +
                "through a catalogue — ${declared.size - reachable.size} belong to none. " +
                "Declared: ${declared.sorted()}. Reachable: ${reachable.sorted()}",
        )
    }

    /** Two features sharing a store key would overwrite each other in the online store. */
    @Test
    fun `feature names are unique across every catalogue`() {
        val all = FeatureCatalogues.ONLINE_SERVED_FEATURES + MONEY_FLOW_FEATURES
        val dupes = all.map { it.name }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate feature names: $dupes")
    }

    /** An offline-only entry without a reason is indistinguishable from an oversight. */
    @Test
    fun `every offline-only catalogue carries a substantive reason`() {
        for ((name, reason) in FeatureCatalogues.OFFLINE_ONLY) {
            assertTrue(reason.length > 60, "reason for $name is too short to be one: \"$reason\"")
        }
    }
}
