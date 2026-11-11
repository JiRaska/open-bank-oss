// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.monitoring

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.monitoring.MonitoringPack
import com.openbank.aml.application.monitoring.PackRule
import com.openbank.aml.domain.monitoring.CountFeatureDefinition
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.aml.domain.monitoring.MovementDirection
import com.openbank.libs.fincrime.pack.CountFeatureRef
import com.openbank.libs.fincrime.pack.CountPackJson
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabularyJson
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Compiles the monitoring pack from data files with the ADR-0354 kernel (ADR-0371 §3).
 *
 * The files shipped in this repository are SYNTHETIC: one scenario shape with placeholder numbers.
 * This loader authenticates nothing and activates nothing — signature, approval and the activation
 * epoch belong to the ADR-0354 pack lifecycle (#13775), which is why evaluation here only ever
 * records, unless a deployment explicitly turns case opening on.
 *
 * `@Startup`: a pack that does not compile, pins an unknown vocabulary code or an undefined feature
 * must stop the boot rather than surface as a job that quietly evaluates nothing.
 */
@ApplicationScoped
class MonitoringPackLoader(
    private val objectMapper: ObjectMapper,
    private val scheme: CounterScheme,
    @ConfigProperty(name = "openbank.aml.monitoring.pack.resource", defaultValue = "aml/monitoring-pack.synthetic.json")
    private val packResource: String,
    @ConfigProperty(
        name = "openbank.aml.monitoring.pack.reasons-resource",
        defaultValue = "aml/monitoring-reasons.synthetic.json",
    )
    private val reasonsResource: String,
    @ConfigProperty(
        name = "openbank.aml.monitoring.pack.severities-resource",
        defaultValue = "aml/monitoring-severities.synthetic.json",
    )
    private val severitiesResource: String,
    @ConfigProperty(
        name = "openbank.aml.monitoring.pack.features-resource",
        defaultValue = "aml/monitoring-count-features.synthetic.json",
    )
    private val featuresResource: String,
) {
    private val log = Logger.getLogger(MonitoringPackLoader::class.java)

    @Produces
    @Startup
    @Singleton
    fun monitoringPack(): MonitoringPack {
        val pack = load(objectMapper, scheme, read(packResource), read(reasonsResource), read(severitiesResource), read(featuresResource))
        log.infof(
            "[aml-monitoring] pack %s rev %d (%s), %d rule(s)",
            pack.identity.id,
            pack.identity.revision,
            pack.identity.contentHash,
            pack.rules.size,
        )
        return pack
    }

    private fun read(resource: String): String =
        Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
            ?.use { String(it.readNBytes(CounterSchemeLoader.MAX_BYTES + 1), Charsets.UTF_8) }
            ?: error("monitoring resource '$resource' not found")

    companion object {
        private val ROOT_FIELDS = setOf("schemaVersion", "id", "revision", "purpose", "counterScheme", "features")
        private val FEATURE_FIELDS = setOf("id", "revision", "currency", "direction", "rail", "band", "windowSeconds")

        fun load(
            objectMapper: ObjectMapper,
            scheme: CounterScheme,
            packJson: String,
            reasonsJson: String,
            severitiesJson: String,
            featuresJson: String,
        ): MonitoringPack {
            val compiled = CountPackJson.compile(
                packJson,
                FincrimeVocabularyJson.compile(reasonsJson),
                FincrimeVocabularyJson.compile(severitiesJson),
            )
            // The rules are read back from the kernel's own canonical form, not from the source text.
            val rules = objectMapper.readTree(compiled.canonicalJson)["rules"].map { rule ->
                val feature = rule["feature"]
                PackRule(
                    id = rule["id"].textValue(),
                    feature = CountFeatureRef(feature["id"].textValue(), feature["revision"].intValue(), feature["hash"].textValue()),
                    reasonCode = rule["reason"].textValue(),
                    severityCode = rule["effect"]["severity"].textValue(),
                )
            }
            return MonitoringPack(compiled, rules, features(objectMapper, featuresJson), scheme)
        }

        /** Strict: an unknown or missing field is an error, never a default. */
        fun features(objectMapper: ObjectMapper, json: String): List<CountFeatureDefinition> {
            require(json.toByteArray(Charsets.UTF_8).size <= CounterSchemeLoader.MAX_BYTES) { "features exceed size limit" }
            val root = objectMapper.readTree(json)
            exact(root, ROOT_FIELDS)
            require(root["schemaVersion"].isInt && root["schemaVersion"].intValue() == 1) { "unsupported features schema" }
            val counterScheme = root["counterScheme"]
            exact(counterScheme, setOf("id", "revision"))
            require(root["features"].isArray) { "features must be an array" }
            return root["features"].map { feature ->
                exact(feature, FEATURE_FIELDS)
                require(feature["revision"].isInt && feature["windowSeconds"].isIntegralNumber) { "expected integers" }
                CountFeatureDefinition(
                    id = text(feature["id"]),
                    revision = feature["revision"].intValue(),
                    schemeId = text(counterScheme["id"]),
                    schemeRevision = counterScheme["revision"].intValue(),
                    currency = text(feature["currency"]),
                    direction = optionalText(feature["direction"])?.let { MovementDirection.valueOf(it) },
                    rail = optionalText(feature["rail"]),
                    band = text(feature["band"]),
                    windowSeconds = feature["windowSeconds"].longValue(),
                )
            }
        }

        private fun exact(node: JsonNode?, expected: Set<String>) {
            require(node != null && node.isObject) { "expected an object" }
            require(node.fieldNames().asSequence().toSet() == expected) { "unknown or missing feature fields" }
        }

        private fun text(node: JsonNode): String {
            require(node.isTextual) { "expected a string" }
            return node.textValue()
        }

        private fun optionalText(node: JsonNode): String? = if (node.isNull) null else text(node)
    }
}
