// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.openbank.libs.fincrime.vocabulary.FincrimeVocabulary
import com.openbank.libs.fincrime.vocabulary.VocabularyCode
import com.openbank.libs.fincrime.vocabulary.VocabularyEntry
import com.openbank.libs.fincrime.vocabulary.VocabularySource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant

class CountPackJsonTest {
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(checkNotNull(javaClass.getResourceAsStream("/fincrime/count-source-v1.schema.json")))
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val until = Instant.parse("2027-01-01T00:00:00Z")
    private val at = from.plusSeconds(100)
    private val fraud = fixture("fraud-count-pack.canonical.json").trim()
    private val aml = fixture("aml-count-source.json")
    private val reasons = vocabulary("fraud.reasons", "fraud", "BURST")
    private val amlReasons = vocabulary("aml.reasons", "aml", "SYNTHETIC_PATTERN")
    private val scale = vocabulary("aml.case-severity", "aml", "EXPEDITED", 7)
    private val input = CountObservation(
        CountFeatureRef("velocity_txn_count_h1", 1, "a".repeat(64)),
        10,
        at.minusSeconds(1),
        at,
    )

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/fincrime/$name")).readText()

    private fun vocabulary(id: String, domain: String, code: String, rank: Int? = null) = FincrimeVocabulary.compile(
        VocabularySource(
            1, id, 1, domain, "synthetic", "fixture-v1", from, until,
            listOf(VocabularyEntry(VocabularyCode(code), "Synthetic", "Synthetic observation only", rank)),
        ),
    )

    private fun edited(edit: (ObjectNode) -> Unit): String =
        (mapper.readTree(fraud) as ObjectNode).also(edit).toString()

    private fun rule(root: ObjectNode) = root["rules"][0] as ObjectNode

    @Test
    fun `committed fraud source preserves existing golden identity and produces real findings`() {
        val pack = CountPackJson.compile(fraud, reasons)
        assertThat(pack.canonicalJson).isEqualTo(fraud)
        assertThat(pack.reference.contentHash).isEqualTo(fixture("fraud-count-pack.sha256").trim())
        val result = pack.evaluate(pack.reference, at, listOf(input)) as CountEvaluation.Evaluated
        assertThat(result.evaluatedRules).isEqualTo(1)
        assertThat(result.findings.single().reason.value).isEqualTo("BURST")
        val reformatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(fraud))
        assertThat(CountPackJson.compile(reformatted, reasons).reference).isEqualTo(pack.reference)
    }

    @Test
    fun `AML data source uses its own scale and same-input replay detects its boundary change`() {
        assertThat(schema.validate(mapper.readTree(aml))).isEmpty()
        val pack = CountPackJson.compile(aml, amlReasons, scale)
        val result = pack.evaluate(pack.reference, at, listOf(input)) as CountEvaluation.Evaluated
        assertThat(result.evaluatedRules).isEqualTo(1)
        assertThat(result.findings.single().effect).isEqualTo(CountEffect.AmlTriage(VocabularyCode("EXPEDITED")))
        val candidate = CountPackJson.compile(aml.replace("\"10\"", "\"11\""), amlReasons, scale)
        val diff = CountPackReplay.compare(pack, candidate, listOf(CountReplayCase("boundary", at, listOf(input))))
        assertThat(diff.single().behaviorChanged).isTrue()
        assertThat(CountPackJson.compile(pack.canonicalJson, amlReasons, scale).reference).isEqualTo(pack.reference)
        assertThatThrownBy {
            CountPackJson.compile(aml, reasons, scale)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `source schema and compiler keep AML effects separate from executable fraud verdicts`() {
        val wrongAml = mapper.readTree(aml) as ObjectNode
        rule(wrongAml).putObject("effect").put("type", "FRAUD_ASSESSMENT").put("verdict", "ALLOW")
        assertThat(schema.validate(wrongAml)).isNotEmpty()
        assertThatThrownBy { CountPackJson.compile(wrongAml.toString(), amlReasons, scale) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val wrongFraud = edited {
            rule(it).putObject("effect").put("type", "AML_TRIAGE").put("severity", "EXPEDITED")
        }
        assertThat(schema.validate(mapper.readTree(wrongFraud))).isNotEmpty()
        assertThatThrownBy { CountPackJson.compile(wrongFraud, reasons) }
            .isInstanceOf(IllegalArgumentException::class.java)
        for (age in listOf("1", "86400")) {
            val json = edited { rule(it).put("maxAgeSeconds", age) }
            assertThat(schema.validate(mapper.readTree(json))).isEmpty()
            val pack = CountPackJson.compile(json, reasons)
            assertThat(
                pack.evaluate(pack.reference, at, listOf(input)),
            ).isInstanceOf(CountEvaluation.Evaluated::class.java)
        }
    }

    @Test
    fun `exact Long count survives source decoding and range overflow never rounds`() {
        val source = edited { rule(it).put("minimum", Long.MAX_VALUE.toString()) }
        val pack = CountPackJson.compile(source, reasons)
        assertThat(pack.canonicalJson).contains("9223372036854775807")
        val result = pack.evaluate(pack.reference, at, listOf(input.copy(value = Long.MAX_VALUE)))
            as CountEvaluation.Evaluated
        assertThat(result.findings).hasSize(1)
        assertThatThrownBy {
            CountPackJson.compile(source.replace("9223372036854775807", "9223372036854775808"), reasons)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @TestFactory
    fun `schema and compiler reject structural defects`(): List<DynamicTest> {
        val defects = listOf(
            edited { it.put("script", "never executed") },
            edited { it.remove("severities") },
            edited { it.put("schemaVersion", 2) },
            edited { it.put("revision", 0) },
            edited { it.put("revision", "1") },
            edited { it.put("revision", 1.5) },
            edited { it.put("revision", 2147483648L) },
            edited { it.put("domain", "SANCTIONS") },
            edited { it.put("rules", "not an array") },
            edited { it.putArray("rules") },
            edited { it.putNull("reasons") },
            edited { it.put("id", 42) },
            edited { (it["reasons"] as ObjectNode).put("hash", "bad") },
            edited { (it["reasons"] as ObjectNode).put("latest", true) },
            edited { rule(it).put("operator", "EXECUTE") },
            edited { rule(it).put("minimum", 10) },
            edited { rule(it).put("minimum", "01") },
            edited { rule(it).put("minimum", "-1") },
            edited { rule(it).put("minimum", "1e2") },
            edited { rule(it).put("maxAgeSeconds", "0") },
            edited { rule(it).put("maxAgeSeconds", "86401") },
            edited { rule(it).put("effect", "REVIEW") },
            edited { (rule(it)["effect"] as ObjectNode).put("type", "RUN_SCRIPT") },
            edited { (rule(it)["effect"] as ObjectNode).put("verdict", "UNKNOWN") },
            edited { (rule(it)["effect"] as ObjectNode).put("verdict", "NEW_ACTION") },
            edited { (rule(it)["effect"] as ObjectNode).put("severity", "LOW") },
            edited { (rule(it)["feature"] as ObjectNode).remove("revision") },
        )
        return defects.mapIndexed { i, json ->
            DynamicTest.dynamicTest("reject source structure $i") {
                assertThat(schema.validate(mapper.readTree(json))).isNotEmpty()
                assertThatThrownBy {
                    CountPackJson.compile(json, reasons)
                }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @TestFactory
    fun `compiler remains authoritative for semantic and syntax failures`(): List<DynamicTest> {
        val defects = listOf(
            "[]", "{}", "null", fraud + " false", "{\"id\":1,\"id\":2}",
            fraud.replace("\"minimum\":\"10\"", "\"minimum\":\"10\",\"minimum\":\"0\""),
            fraud.replace("\"revision\":1", "\"revision\":1e2147483647"),
            edited { it.put("effectiveFrom", "not-a-date") },
            edited { it.put("effectiveUntil", from.toString()) },
            edited { rule(it).put("reason", "UNDECLARED") },
            edited { (it["reasons"] as ObjectNode).put("revision", 2) },
            edited { it.withArray("rules").add(rule(it).deepCopy()) },
            "[".repeat(66) + "0" + "]".repeat(66),
        )
        return defects.mapIndexed { i, json ->
            DynamicTest.dynamicTest("reject invalid source $i") {
                assertThatThrownBy {
                    CountPackJson.compile(json, reasons)
                }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `bounded source accepts 64 rules and exact byte limit but refuses oversized documents`() {
        val root = mapper.readTree(fraud) as ObjectNode
        val one = rule(root).deepCopy()
        val rules = root.putArray("rules")
        repeat(64) { rules.add(one.deepCopy().put("id", "r$it")) }
        assertThat(schema.validate(root)).isEmpty()
        val pack = CountPackJson.compile(root.toString(), reasons)
        assertThat((pack.evaluate(pack.reference, at, listOf(input)) as CountEvaluation.Evaluated).evaluatedRules)
            .isEqualTo(64)
        rules.add(one.deepCopy().put("id", "r64"))
        assertThat(schema.validate(root)).isNotEmpty()
        assertThatThrownBy {
            CountPackJson.compile(root.toString(), reasons)
        }.isInstanceOf(IllegalArgumentException::class.java)
        val max = fraud.padEnd(CountPackJson.MAX_SOURCE_BYTES)
        assertThat(CountPackJson.compile(max, reasons).canonicalJson).isEqualTo(fraud)
        assertThatThrownBy { CountPackJson.compile(max + " ", reasons) }.hasMessageContaining("size limit")
        assertThatThrownBy { CountPackJson.compile("é".repeat(CountPackJson.MAX_SOURCE_BYTES), reasons) }
            .hasMessageContaining("byte limit")
    }

    @Test
    fun `schema is closed and offline and explicitly distinguishes structural checks from trust`() {
        assertThat(schema.validate(mapper.readTree(fraud))).isEmpty()
        val text = fixture("count-source-v1.schema.json")
        assertThat(text).doesNotContain("\"$" + "ref\": \"http")
        val objects = mapper.readTree(text).findParents("type")
            .filter { it["type"].asText() == "object" }
        assertThat(objects).isNotEmpty.allMatch { it["additionalProperties"].asBoolean() == false }
        assertThat(text).contains("does not establish authenticity, approval or activation")
    }
}
