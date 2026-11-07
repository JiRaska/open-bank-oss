// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.vocabulary

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant

class FincrimeVocabularyTest {
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val until = Instant.parse("2027-01-01T00:00:00Z")
    private val review = VocabularyEntry(VocabularyCode("REVIEW"), "Review", "Analyst review required", 10)
    private val urgent = VocabularyEntry(VocabularyCode("URGENT"), "Urgent", "Expedited analyst review", 20)

    private fun source() = VocabularySource(
        1, "aml.case-severity", 1, "aml", "case-triage", "synthetic-fixture-v1", from, until, listOf(review, urgent),
    )

    @Test
    fun `AML source compiles to pinned canonical bytes independent of input order`() {
        val compiled = FincrimeVocabulary.compile(source())
        val golden = checkNotNull(javaClass.getResource("/fincrime/aml-vocabulary.canonical.json")).readText().trimEnd()
        assertThat(compiled.canonicalJson).isEqualTo(golden)
        assertThat(
            compiled.reference.contentHash,
        ).isEqualTo("5039f61efc88c8a5eb57962abc9f4815579cb2dc0966a540f0e443843bcabc62")
        assertThat(FincrimeVocabulary.compile(source().copy(entries = listOf(urgent, review))).reference)
            .isEqualTo(compiled.reference)
        assertThat(compiled.resolve(compiled.reference, urgent.code, from))
            .isEqualTo(VocabularyResolution.Resolved(compiled.reference, urgent))
    }

    @Test
    fun `fraud reason vocabulary introduces new codes without a production enum or scale`() {
        val entry = VocabularyEntry(VocabularyCode("DEVICE_CHANGE"), "Device changed", "New device observed")
        val data = source().copy(
            id = "fraud.reasons",
            domain = "fraud",
            purpose = "assessment-explanation",
            entries = listOf(entry),
            effectiveUntil = null,
        )
        val compiled = FincrimeVocabulary.compile(data)
        assertThat(compiled.resolve(compiled.reference, entry.code, Instant.MAX))
            .isEqualTo(VocabularyResolution.Resolved(compiled.reference, entry))
        assertThat(compiled.resolve(compiled.reference, review.code, from)).isEqualTo(VocabularyResolution.UnknownCode)
    }

    @Test
    fun `same code in a different concept cannot be resolved by name or ordinal`() {
        val aml = FincrimeVocabulary.compile(source())
        val kyc = FincrimeVocabulary.compile(
            source().copy(id = "kyc.customer-rating", domain = "kyc", purpose = "customer-rating"),
        )
        assertThat(aml.resolve(kyc.reference, review.code, from)).isEqualTo(VocabularyResolution.RevisionMismatch)
        assertThat(kyc.resolve(aml.reference, review.code, from)).isEqualTo(VocabularyResolution.RevisionMismatch)
    }

    @Test
    fun `unknown codes and labels do not become the lowest rank`() {
        val compiled = FincrimeVocabulary.compile(source())
        listOf("MISSING", "Review", "review").forEach { code ->
            assertThat(compiled.resolve(compiled.reference, VocabularyCode(code), from))
                .isEqualTo(VocabularyResolution.UnknownCode)
        }
    }

    @Test
    fun `effective dates are explicit inclusive from and exclusive until including replay`() {
        val compiled = FincrimeVocabulary.compile(source())
        assertThat(compiled.resolve(compiled.reference, review.code, from.minusNanos(1)))
            .isEqualTo(VocabularyResolution.OutsideValidity)
        assertThat(compiled.resolve(compiled.reference, review.code, until))
            .isEqualTo(VocabularyResolution.OutsideValidity)
        assertThat(compiled.resolve(compiled.reference, review.code, until.minusNanos(1)))
            .isInstanceOf(VocabularyResolution.Resolved::class.java)
        assertThat(compiled.resolve(compiled.reference, review.code, from))
            .isInstanceOf(VocabularyResolution.Resolved::class.java)
    }

    @Test
    fun `all reference components must match including a same-id same-revision changed digest`() {
        val compiled = FincrimeVocabulary.compile(source())
        listOf(
            compiled.reference.copy(id = "other"),
            compiled.reference.copy(revision = 2),
            compiled.reference.copy(contentHash = "0".repeat(64)),
        ).forEach { ref ->
            assertThat(compiled.resolve(ref, review.code, from)).isEqualTo(VocabularyResolution.RevisionMismatch)
        }
    }

    @TestFactory
    fun `each semantic or provenance change invalidates the previous binding`(): List<DynamicTest> {
        val original = source()
        val compiled = FincrimeVocabulary.compile(original)
        val changes = mapOf(
            "identity" to original.copy(id = "other"),
            "revision" to original.copy(revision = 2),
            "domain" to original.copy(domain = "kyc"),
            "purpose" to original.copy(purpose = "customer-rating"),
            "provenance" to original.copy(provenance = "another-fixture"),
            "start" to original.copy(effectiveFrom = from.minusSeconds(1)),
            "end" to original.copy(effectiveUntil = until.plusSeconds(1)),
            "unbounded-end" to original.copy(effectiveUntil = null),
            "code" to original.copy(entries = listOf(review.copy(code = VocabularyCode("NEW")), urgent)),
            "label" to original.copy(entries = listOf(review.copy(label = "Changed"), urgent)),
            "meaning" to original.copy(entries = listOf(review.copy(meaning = "Changed"), urgent)),
            "rank" to original.copy(entries = listOf(review.copy(rank = 30), urgent)),
            "unordered" to original.copy(entries = listOf(review.copy(rank = null), urgent.copy(rank = null))),
        )
        return changes.map { (name, data) ->
            DynamicTest.dynamicTest(name) {
                val changed = FincrimeVocabulary.compile(data)
                assertThat(changed.reference.contentHash).isNotEqualTo(compiled.reference.contentHash)
                assertThat(changed.resolve(compiled.reference, review.code, from))
                    .isEqualTo(VocabularyResolution.RevisionMismatch)
            }
        }
    }

    @Test
    fun `caller mutation cannot change an already compiled snapshot`() {
        val input = mutableListOf(review, urgent)
        val compiled = FincrimeVocabulary.compile(source().copy(entries = input))
        val before = compiled.canonicalJson
        input.clear()
        assertThat(compiled.entries).containsExactly(review, urgent)
        assertThat(compiled.canonicalJson).isEqualTo(before)
        assertThat(compiled.resolve(compiled.reference, review.code, from))
            .isEqualTo(VocabularyResolution.Resolved(compiled.reference, review))
        assertThatThrownBy { (compiled.entries as MutableList<VocabularyEntry>).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `ordering comes from data not alphabetical order and labels may repeat`() {
        val compiled = FincrimeVocabulary.compile(
            source().copy(entries = listOf(review.copy(rank = 99), urgent.copy(label = "Review"))),
        )
        val result = compiled.resolve(compiled.reference, review.code, from) as VocabularyResolution.Resolved
        assertThat(result.entry.rank).isEqualTo(99)
        assertThat(compiled.entries.map { it.label }).containsExactly("Review", "Review")
    }

    @Test
    fun `Unicode labels are retained and canonical JSON escapes quotes and backslashes`() {
        val text = "Příliš \"žluťoučký\" \\ kůň 🐎"
        val compiled = FincrimeVocabulary.compile(source().copy(entries = listOf(review.copy(label = text))))
        val resolved = compiled.resolve(compiled.reference, review.code, from) as VocabularyResolution.Resolved
        assertThat(resolved.entry.label).isEqualTo(text)
        assertThat(compiled.canonicalJson).contains("Příliš", "kůň 🐎", "\\\"žluťoučký\\\"", "\\\\")
    }

    @TestFactory
    fun `invalid or unbounded source never becomes a compiled revision`(): List<DynamicTest> {
        val data = source()
        val invalid = mapOf(
            "schema" to data.copy(schemaVersion = 2),
            "id" to data.copy(id = "bad id"),
            "revision" to data.copy(revision = 0),
            "domain" to data.copy(domain = ""),
            "purpose" to data.copy(purpose = "bad purpose"),
            "blank provenance" to data.copy(provenance = " "),
            "long provenance" to data.copy(provenance = "x".repeat(FincrimeVocabulary.MAX_PROVENANCE_LENGTH + 1)),
            "empty validity" to data.copy(effectiveUntil = from),
            "backward validity" to data.copy(effectiveUntil = from.minusSeconds(1)),
            "no entries" to data.copy(entries = emptyList()),
            "too many entries" to data.copy(entries = List(FincrimeVocabulary.MAX_ENTRIES + 1) { review }),
            "duplicate code" to data.copy(entries = listOf(review, review.copy(label = "Another"))),
            "blank label" to data.copy(entries = listOf(review.copy(label = ""))),
            "long label" to
                data.copy(entries = listOf(review.copy(label = "x".repeat(FincrimeVocabulary.MAX_LABEL_LENGTH + 1)))),
            "blank meaning" to data.copy(entries = listOf(review.copy(meaning = " "))),
            "long meaning" to
                data.copy(
                    entries = listOf(review.copy(meaning = "x".repeat(FincrimeVocabulary.MAX_MEANING_LENGTH + 1))),
                ),
            "control character" to data.copy(entries = listOf(review.copy(label = "bad\nlabel"))),
            "NUL" to data.copy(entries = listOf(review.copy(meaning = "bad\u0000meaning"))),
            "malformed Unicode" to data.copy(provenance = "bad\uD800"),
            "negative rank" to data.copy(entries = listOf(review.copy(rank = -1))),
            "mixed ranks" to data.copy(entries = listOf(review, urgent.copy(rank = null))),
            "duplicate ranks" to data.copy(entries = listOf(review, urgent.copy(rank = review.rank))),
        )
        return invalid.map { (name, source) ->
            DynamicTest.dynamicTest(name) {
                assertThatThrownBy {
                    FincrimeVocabulary.compile(source)
                }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `maximum sizes are accepted without a new vocabulary in production source`() {
        val entries = List(FincrimeVocabulary.MAX_ENTRIES) {
            VocabularyEntry(
                VocabularyCode("C" + it.toString().padStart(63, '0')),
                "x".repeat(FincrimeVocabulary.MAX_LABEL_LENGTH),
                "y".repeat(FincrimeVocabulary.MAX_MEANING_LENGTH),
                it,
            )
        }
        val compiled = FincrimeVocabulary.compile(
            source().copy(entries = entries, provenance = "x".repeat(FincrimeVocabulary.MAX_PROVENANCE_LENGTH)),
        )
        assertThat(compiled.entries).hasSize(FincrimeVocabulary.MAX_ENTRIES)
        assertThat(compiled.resolve(compiled.reference, entries.last().code, from))
            .isEqualTo(VocabularyResolution.Resolved(compiled.reference, entries.last()))
    }

    @Test
    fun `malformed identity and reference are rejected at construction`() {
        listOf("", "1CODE", "bad code", "A".repeat(65), "ŽLUTÁ").forEach {
            assertThatThrownBy { VocabularyCode(it) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        val ref = FincrimeVocabulary.compile(source()).reference
        assertThatThrownBy { ref.copy(id = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ref.copy(revision = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        listOf("", "0".repeat(63), "G".repeat(64), "A".repeat(64)).forEach {
            assertThatThrownBy { ref.copy(contentHash = it) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
