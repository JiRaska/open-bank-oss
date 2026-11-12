// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.monitoring

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.domain.monitoring.AmountBand
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.libs.audit.AuditChain
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal

/**
 * Reads the counter scheme — bucket width and amount bands — from a data file (ADR-0371 §2).
 *
 * The file shipped in this repository is SYNTHETIC: its bands are placeholders that mean nothing.
 * A real scheme is strategy content and arrives with an approved ADR-0354 pack, not in this tree.
 *
 * `@Startup`: a malformed scheme must stop the boot. A lazily created bean would instead fail the
 * first attribution tick, long after the deploy went green.
 */
@ApplicationScoped
class CounterSchemeLoader(
    private val objectMapper: ObjectMapper,
    @ConfigProperty(
        name = "openbank.aml.monitoring.counter-scheme.resource",
        defaultValue = "aml/monitoring-counter-scheme.synthetic.json",
    )
    private val resource: String,
) {
    private val log = Logger.getLogger(CounterSchemeLoader::class.java)

    @Produces
    @Startup
    @Singleton
    fun counterScheme(): CounterScheme {
        val json = Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
            ?.use { String(it.readNBytes(MAX_BYTES + 1), Charsets.UTF_8) }
            ?: error("counter scheme resource '$resource' not found")
        val scheme = parse(objectMapper, json)
        log.infof(
            "[aml-monitoring] counter scheme %s rev %d (%s), %d band(s), bucket %ds",
            scheme.id,
            scheme.revision,
            scheme.contentHash,
            scheme.bands.size,
            scheme.bucketSeconds,
        )
        return scheme
    }

    companion object {
        const val MAX_BYTES = 65_536
        private val ROOT_FIELDS = setOf("schemaVersion", "id", "revision", "purpose", "bucketSeconds", "bands")
        private val BAND_FIELDS = setOf("code", "currency", "lowerInclusive", "upperExclusive")

        /** Strict: an unknown or missing field is an error, never a default. */
        fun parse(objectMapper: ObjectMapper, json: String): CounterScheme {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "counter scheme exceeds size limit" }
            val root = objectMapper.readTree(json)
            exactFields(root, ROOT_FIELDS)
            require(root["schemaVersion"].isInt && root["schemaVersion"].intValue() == 1) {
                "unsupported counter scheme schema"
            }
            require(root["revision"].isInt && root["bucketSeconds"].isIntegralNumber) { "expected integers" }
            val bands = root["bands"]
            require(bands.isArray) { "bands must be an array" }
            return CounterScheme(
                id = text(root["id"]),
                revision = root["revision"].intValue(),
                contentHash = AuditChain.sha256Hex(json),
                bucketSeconds = root["bucketSeconds"].longValue(),
                bands = bands.map { band ->
                    exactFields(band, BAND_FIELDS)
                    AmountBand(
                        code = text(band["code"]),
                        currency = text(band["currency"]),
                        lowerInclusive = decimal(band["lowerInclusive"]),
                        upperExclusive = decimal(band["upperExclusive"]),
                    )
                },
            )
        }

        private fun exactFields(node: JsonNode?, expected: Set<String>) {
            require(node != null && node.isObject) { "expected an object" }
            require(node.fieldNames().asSequence().toSet() == expected) { "unknown or missing scheme fields" }
        }

        private fun text(node: JsonNode): String {
            require(node.isTextual) { "expected a string" }
            return node.textValue()
        }

        /** Amounts are decimal STRINGS: a JSON number would pass through binary floating point. */
        private fun decimal(node: JsonNode): BigDecimal {
            val value = text(node)
            require(value.matches(Regex("(0|[1-9][0-9]{0,14})(\\.[0-9]{1,4})?"))) { "expected a decimal amount string" }
            return BigDecimal(value)
        }
    }
}
