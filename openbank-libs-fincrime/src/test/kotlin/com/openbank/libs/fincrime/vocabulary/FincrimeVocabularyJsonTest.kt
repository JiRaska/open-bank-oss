// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.vocabulary

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.openbank.libs.fincrime.pack.CountEvaluation
import com.openbank.libs.fincrime.pack.CountFeatureRef
import com.openbank.libs.fincrime.pack.CountObservation
import com.openbank.libs.fincrime.pack.CountPackJson
import com.openbank.libs.fincrime.pack.CountPackReplay
import com.openbank.libs.fincrime.pack.CountReplayCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant

class FincrimeVocabularyJsonTest {
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(checkNotNull(javaClass.getResourceAsStream("/fincrime/vocabulary-source-v1.schema.json")))
    private val source = fixture("aml-vocabulary.canonical.json").trim()
    private val at = Instant.parse("2026-01-01T00:01:40Z")
    private val observation = CountObservation(
        CountFeatureRef("velocity_txn_count_h1", 1, "a".repeat(64)),
        10,
        at.minusSeconds(1),
        at,
    )

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/fincrime/$name")).readText()
    private fun edited(edit: (ObjectNode) -> Unit): String =
        (mapper.readTree(source) as ObjectNode).also(edit).toString()
    private fun entry(root: ObjectNode) = root["entries"][0] as ObjectNode

    @Test
    fun `existing vocabulary golden round trips without identity or ordering changes`() {
        val vocabulary = FincrimeVocabularyJson.compile(source)
        assertThat(vocabulary.canonicalJson).isEqualTo(source)
        val reordered = edited {
            val first = it.withArray("entries").remove(0)
            it.withArray("entries").add(first)
        }
        assertThat(FincrimeVocabularyJson.compile(reordered).reference).isEqualTo(vocabulary.reference)
        assertThat(vocabulary.resolve(vocabulary.reference, VocabularyCode("REVIEW"), at))
            .isInstanceOf(VocabularyResolution.Resolved::class.java)
        assertThat(schema.validate(mapper.readTree(source))).isEmpty()
    }

    @Test
    fun `both domains evaluate from JSON vocabulary and pack sources with exact existing pins`() {
        val fraudReasons = FincrimeVocabularyJson.compile(fixture("fraud-reasons-source.json"))
        val amlReasons = FincrimeVocabularyJson.compile(fixture("aml-reasons-source.json"))
        val severity = FincrimeVocabularyJson.compile(fixture("aml-severity-source.json"))
        val fraud = CountPackJson.compile(fixture("fraud-count-pack.canonical.json"), fraudReasons)
        val aml = CountPackJson.compile(fixture("aml-count-source.json"), amlReasons, severity)
        assertThat(fraud.reference.contentHash).isEqualTo(fixture("fraud-count-pack.sha256").trim())
        for (pack in listOf(aml, fraud)) {
            val result = pack.evaluate(pack.reference, at, listOf(observation)) as CountEvaluation.Evaluated
            assertThat(result.evaluatedRules).isEqualTo(1)
            assertThat(result.findings).hasSize(1)
        }
        for (name in listOf("aml-reasons-source.json", "aml-severity-source.json", "fraud-reasons-source.json")) {
            assertThat(schema.validate(mapper.readTree(fixture(name)))).isEmpty()
        }
        assertThatThrownBy { CountPackJson.compile(fixture("aml-count-source.json"), fraudReasons, severity) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `authored meaning change invalidates old pin and is visible in same-input replay`() {
        val before = FincrimeVocabularyJson.compile(fixture("fraud-reasons-source.json"))
        val after = FincrimeVocabularyJson.compile(
            fixture("fraud-reasons-source.json").replace("Synthetic observation only", "Changed synthetic meaning"),
        )
        val packSource = fixture("fraud-count-pack.canonical.json")
        val baseline = CountPackJson.compile(packSource, before)
        assertThat(after.reference).isNotEqualTo(before.reference)
        assertThatThrownBy {
            CountPackJson.compile(packSource, after)
        }.isInstanceOf(IllegalArgumentException::class.java)
        val candidate = CountPackJson.compile(
            packSource.replace(before.reference.contentHash, after.reference.contentHash),
            after,
        )
        val difference = CountPackReplay.compare(
            baseline,
            candidate,
            listOf(CountReplayCase("meaning", at, listOf(observation))),
        ).single()
        assertThat(difference.behaviorChanged).isTrue()
        assertThat(difference.baselinePack).isEqualTo(baseline.reference)
        assertThat(difference.candidatePack).isEqualTo(candidate.reference)
    }

    @Test
    fun `explicit null means unordered rank or open-ended validity and zero rank remains data`() {
        val json = edited {
            it.putNull("effectiveUntil")
            for (e in it.withArray("entries")) (e as ObjectNode).putNull("rank")
        }
        val vocabulary = FincrimeVocabularyJson.compile(json)
        assertThat(schema.validate(mapper.readTree(json))).isEmpty()
        assertThat(vocabulary.entries).allMatch { it.rank == null }
        assertThat(
            vocabulary.resolve(vocabulary.reference, VocabularyCode("REVIEW"), Instant.parse("2099-01-01T00:00:00Z")),
        )
            .isInstanceOf(VocabularyResolution.Resolved::class.java)
        assertThat(FincrimeVocabularyJson.compile(vocabulary.canonicalJson).reference).isEqualTo(vocabulary.reference)
        val zero = edited { entry(it).put("rank", 0) }
        assertThat(FincrimeVocabularyJson.compile(zero).entries.first().rank).isZero()
    }

    @TestFactory
    fun `structural defects are rejected by schema and compiler`(): List<DynamicTest> = listOf(
        edited { it.put("schemaVersion", 2) }, edited { it.put("revision", 0) },
        edited { it.put("revision", 1.5) }, edited { it.put("revision", 2147483648L) },
        edited { it.put("revision", "1") }, edited { it.put("latest", true) },
        edited { it.remove("effectiveUntil") }, edited { it.put("entries", "bad") },
        edited { it.putArray("entries") }, edited { it.put("id", "bad id") },
        edited { it.put("domain", 1) }, edited { it.put("purpose", false) },
        edited { it.putNull("effectiveFrom") }, edited { it.put("provenance", "x".repeat(513)) },
        edited { entry(it).remove("rank") }, edited { entry(it).put("rank", -1) },
        edited { entry(it).put("rank", 1.1) }, edited { entry(it).put("rank", 2147483648L) },
        edited { entry(it).put("rank", "1") }, edited { entry(it).put("script", "unused") },
        edited { entry(it).putNull("code") }, edited { entry(it).put("label", "x".repeat(257)) },
        edited { entry(it).put("meaning", "x".repeat(1025)) },
    ).mapIndexed { index, json ->
        DynamicTest.dynamicTest("reject structure $index") {
            assertThat(schema.validate(mapper.readTree(json))).isNotEmpty()
            assertThatThrownBy {
                FincrimeVocabularyJson.compile(json)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @TestFactory
    fun `semantic and malformed input fail explicitly`(): List<DynamicTest> = listOf(
        "null", "[]", "{}", source + " false", source.replace("\"revision\":1", "\"revision\":1e2147483647"),
        source.replace("\"revision\":1", "\"revision\":1,\"revision\":2"),
        source.replace("\"rank\":10", "\"rank\":10,\"rank\":0"),
        "[".repeat(66) + "0" + "]".repeat(66),
        edited { it.put("effectiveFrom", "not-an-instant") },
        edited { it.put("effectiveUntil", "2025-01-01T00:00:00Z") },
        edited { it.put("provenance", " ") }, edited { entry(it).put("label", "\u0000") },
        edited { entry(it).put("meaning", " ") },
        source.replace("Analyst review required", "\\uD800"),
        edited { entry(it).put("rank", 20) }, edited { entry(it).putNull("rank") },
        edited { it.withArray("entries").add(entry(it).deepCopy()) },
        edited { it.putArray("entries").add(1) },
    ).mapIndexed { index, json ->
        DynamicTest.dynamicTest("reject invalid source $index") {
            assertThatThrownBy {
                FincrimeVocabularyJson.compile(json)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `bounds apply before parse and at 512 entries without truncation`() {
        val json = edited {
            val sample = entry(it).deepCopy()
            val entries = it.putArray("entries")
            repeat(512) { i -> entries.add(sample.deepCopy().put("code", "C$i").put("rank", i)) }
        }
        assertThat(schema.validate(mapper.readTree(json))).isEmpty()
        assertThat(FincrimeVocabularyJson.compile(json).entries).hasSize(512)
        val oversized = mapper.readTree(json) as ObjectNode
        oversized.withArray("entries").add(entry(oversized).deepCopy().put("code", "Extra").put("rank", 512))
        assertThat(schema.validate(oversized)).isNotEmpty()
        assertThatThrownBy { FincrimeVocabularyJson.compile(oversized.toString()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val exact = source.padEnd(FincrimeVocabularyJson.MAX_SOURCE_BYTES)
        assertThat(FincrimeVocabularyJson.compile(exact).canonicalJson).isEqualTo(source)
        assertThatThrownBy { FincrimeVocabularyJson.compile(exact + " ") }.hasMessageContaining("size limit")
        assertThatThrownBy { FincrimeVocabularyJson.compile("é".repeat(FincrimeVocabularyJson.MAX_SOURCE_BYTES)) }
            .hasMessageContaining("byte limit")
    }
}
