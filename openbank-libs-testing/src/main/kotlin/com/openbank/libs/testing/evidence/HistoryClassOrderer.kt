// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.testing.evidence

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Advisory SECONDARY orderer for `quarkus.test.class-orderer`. Quarkus retains its primary
 * resource/profile grouping; this class only permutes descriptors, never selecting tests.
 * Missing or unusable history retains Quarkus's default alphabetical secondary ordering.
 */
class HistoryClassOrderer internal constructor(private val priorityLoader: () -> Map<String, Int>) : ClassOrderer {
    constructor() : this({
        readPriorities(
            System.getProperty("openbank.test.priority-file"),
            System.getProperty("openbank.test.priority-component"),
            Instant.now(),
        )
    })

    override fun orderClasses(context: ClassOrdererContext) {
        ClassOrderer.ClassName().orderClasses(context)
        val priorities = priorityLoader()
        context.classDescriptors.sortBy { priorities[it.testClass.name] ?: Int.MAX_VALUE }
    }

    internal companion object {
        private const val MAX_BYTES = 256 * 1024
        private const val MAX_CLASSES = 2000
        private const val MAX_AGE_DAYS = 7L
        private const val MAX_FUTURE_MINUTES = 5L
        private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        private val COMPONENT = Regex("openbank-[a-z0-9]+(?:-[a-z0-9]+)*")
        private val FIELDS = setOf("schemaVersion", "component", "generatedAt", "classes")

        internal fun readPriorities(file: String?, component: String?, now: Instant): Map<String, Int> {
            if (file.isNullOrBlank() || component == null || !COMPONENT.matches(component)) return emptyMap()
            return try {
                val path = Path.of(file)
                if (!Files.isRegularFile(path)) return emptyMap()
                val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) return emptyMap()
                val document = ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes)
                parsePriorities(document, component, now)
            } catch (_: IOException) {
                // History is an optional scheduling hint. Its failure cannot suppress the full suite.
                emptyMap()
            } catch (_: InvalidPathException) {
                emptyMap()
            } catch (_: DateTimeParseException) {
                emptyMap()
            } catch (_: SecurityException) {
                emptyMap()
            }
        }

        private fun parsePriorities(document: JsonNode?, component: String, now: Instant): Map<String, Int> {
            if (document == null || !document.isObject || document.fieldNames().asSequence().toSet() != FIELDS) {
                return emptyMap()
            }
            val versionMatches = document["schemaVersion"].isIntegralNumber && document["schemaVersion"].asText() == "1"
            val componentMatches = document["component"].isTextual && document["component"].textValue() == component
            if (!versionMatches || !componentMatches || !document["generatedAt"].isTextual) {
                return emptyMap()
            }
            val generatedAt = Instant.parse(document["generatedAt"].textValue())
            if (
                generatedAt.isBefore(now.minus(Duration.ofDays(MAX_AGE_DAYS))) ||
                generatedAt.isAfter(now.plus(Duration.ofMinutes(MAX_FUTURE_MINUTES)))
            ) {
                return emptyMap()
            }
            return classPriorities(document["classes"])
        }

        private fun classPriorities(classes: JsonNode): Map<String, Int> {
            if (!classes.isArray || classes.size() > MAX_CLASSES) return emptyMap()
            val names = classes.map {
                if (!it.isTextual || !CLASS_NAME.matches(it.textValue())) return emptyMap()
                it.textValue()
            }
            if (names.toSet().size != names.size) return emptyMap()
            return names.withIndex().associate { (index, name) -> name to index }
        }
    }
}
