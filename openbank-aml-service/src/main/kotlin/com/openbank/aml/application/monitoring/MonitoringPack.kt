// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.monitoring

import com.openbank.aml.domain.monitoring.CountFeatureDefinition
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.aml.domain.monitoring.PackIdentity
import com.openbank.libs.audit.AuditChain
import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.pack.CompiledCountPack
import com.openbank.libs.fincrime.pack.CountFeatureRef
import com.openbank.libs.fincrime.pack.PackDomain

/** One rule of the compiled pack, as its canonical form states it. */
data class PackRule(val id: String, val feature: CountFeatureRef, val reasonCode: String, val severityCode: String)

/**
 * The AML count pack this deployment evaluates, with the features its rules pin (ADR-0371 §3).
 *
 * Construction fails — it does not default — when a rule pins a feature that is not defined under
 * exactly that id, revision and hash, when a feature belongs to another counter scheme or names a
 * band the scheme does not have, or when a rule's severity is not a case severity. Nothing about a
 * scenario is decided in this class: thresholds are the pack's, windows and bands are the features'.
 */
class MonitoringPack(
    val compiled: CompiledCountPack,
    val rules: List<PackRule>,
    definitions: List<CountFeatureDefinition>,
    scheme: CounterScheme,
) {
    val identity = PackIdentity(compiled.reference.id, compiled.reference.revision, compiled.reference.contentHash)

    /** Only the features some rule pins, by feature id. */
    val features: Map<String, CountFeatureDefinition>

    private val severities: Map<String, AmlCaseSeverity>

    init {
        require(compiled.domain == PackDomain.AML) { "the monitoring pack must be an AML pack" }
        require(rules.isNotEmpty()) { "the monitoring pack has no rules" }
        val defined = definitions.associateBy { it.id }
        require(defined.size == definitions.size) { "feature ids are unique" }
        val bands = scheme.bands.map { it.code }.toSet() + CounterScheme.ALL_BAND
        features = rules.map { it.feature }.distinct().associate { ref ->
            val definition = requireNotNull(defined[ref.id]) { "pack pins undefined feature '${ref.id}'" }
            require(referenceOf(definition) == ref) { "pack pins another revision or content of feature '${ref.id}'" }
            require(definition.schemeId == scheme.id && definition.schemeRevision == scheme.revision) {
                "feature '${ref.id}' belongs to another counter scheme"
            }
            require(definition.band in bands) { "feature '${ref.id}' names a band the scheme does not define" }
            ref.id to definition
        }
        severities = rules.associate { rule ->
            rule.id to requireNotNull(AmlCaseSeverity.fromWire(rule.severityCode)) {
                "rule '${rule.id}' names severity '${rule.severityCode}', which is not a case severity"
            }
        }
    }

    fun severityOf(scenarioCode: String): AmlCaseSeverity = severities.getValue(scenarioCode)

    /** Longest window any pinned feature looks back over. */
    val longestWindowSeconds: Long = features.values.maxOf { it.windowSeconds }

    companion object {
        fun referenceOf(definition: CountFeatureDefinition) =
            CountFeatureRef(definition.id, definition.revision, AuditChain.sha256Hex(definition.canonical))
    }
}
