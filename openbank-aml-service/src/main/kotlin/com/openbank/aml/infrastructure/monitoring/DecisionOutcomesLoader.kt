// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.monitoring

import com.openbank.aml.application.report.DecisionOutcomes
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabularyJson
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Compiles the decision-outcome vocabulary with the ADR-0353 kernel; a malformed file stops the boot. */
@ApplicationScoped
class DecisionOutcomesLoader(
    @ConfigProperty(
        name = "openbank.aml.monitoring.decision-outcomes.resource",
        defaultValue = "aml/decision-outcomes.provisional.json",
    )
    private val resource: String,
) {
    @Produces
    @Startup
    @Singleton
    fun decisionOutcomes(): DecisionOutcomes {
        val json = Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
            ?.use { String(it.readNBytes(FincrimeVocabularyJson.MAX_SOURCE_BYTES + 1), Charsets.UTF_8) }
            ?: error("decision outcome vocabulary '$resource' not found")
        return DecisionOutcomes(FincrimeVocabularyJson.compile(json))
    }
}
