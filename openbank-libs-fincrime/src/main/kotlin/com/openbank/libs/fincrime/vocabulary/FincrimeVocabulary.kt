// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.vocabulary

import com.openbank.libs.audit.AuditChain
import com.openbank.libs.audit.CanonicalJson
import java.time.Instant
import java.util.Collections

/** Case-sensitive business identity, never a display label or an enum ordinal (ADR-0354 §2). */
data class VocabularyCode(val value: String) {
    init {
        requireIdentifier(value)
    }
}

/** An exact revision binding. A digest is integrity evidence, not approval or authenticity. */
data class VocabularyRef(val id: String, val revision: Int, val contentHash: String) {
    init {
        requireIdentifier(id)
        require(revision > 0) { "revision must be positive" }
        require(contentHash.matches(Regex("[0-9a-f]{64}"))) { "contentHash must be a lowercase SHA-256" }
    }
}

/** Rank is explicit scale data; null denotes an unordered vocabulary such as reason codes. */
data class VocabularyEntry(val code: VocabularyCode, val label: String, val meaning: String, val rank: Int? = null)

/** Input data only. Compilation validates and snapshots it; do not use this as an activated pack. */
data class VocabularySource(
    val schemaVersion: Int,
    val id: String,
    val revision: Int,
    val domain: String,
    val purpose: String,
    val provenance: String,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val entries: List<VocabularyEntry>,
)

/** Closed interpreter outcomes; business codes themselves remain versioned data. */
sealed interface VocabularyResolution {
    data class Resolved(val reference: VocabularyRef, val entry: VocabularyEntry) : VocabularyResolution
    data object RevisionMismatch : VocabularyResolution
    data object OutsideValidity : VocabularyResolution
    data object UnknownCode : VocabularyResolution
}

/**
 * Immutable, bounded vocabulary revision for pack authoring and replay. No registry, latest-version
 * lookup, clock, I/O, activation or implicit conversion between AML, KYC and fraud concepts.
 *
 * [canonicalJson] uses the existing kernel codec, with entries sorted by stable code. It binds
 * schema, domain, purpose, provenance, effective dates, labels, meanings and explicit ranks. Input
 * list order is immaterial; changing meaning, rank or metadata produces a different [reference].
 * Approval, signature verification and epoch selection remain responsibilities of #13775's pack
 * lifecycle. Successful [resolve] alone MUST NOT authorize activation or a payment decision.
 */
class FincrimeVocabulary private constructor(
    val reference: VocabularyRef,
    val domain: String,
    val purpose: String,
    val canonicalJson: String,
    val entries: List<VocabularyEntry>,
    private val effectiveFrom: Instant,
    private val effectiveUntil: Instant?,
) {
    private val byCode = entries.associateBy { it.code }

    /** Exact reference first; validity is [from, until) at the caller's explicit decision instant. */
    fun resolve(reference: VocabularyRef, code: VocabularyCode, at: Instant): VocabularyResolution = when {
        reference != this.reference -> VocabularyResolution.RevisionMismatch
        at < effectiveFrom || (effectiveUntil != null && at >= effectiveUntil) -> VocabularyResolution.OutsideValidity
        else -> byCode[code]?.let { VocabularyResolution.Resolved(this.reference, it) }
            ?: VocabularyResolution.UnknownCode
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 512
        const val MAX_LABEL_LENGTH = 256
        const val MAX_MEANING_LENGTH = 1024
        const val MAX_PROVENANCE_LENGTH = 512

        /** Reject malformed input before canonicalisation; only compiled snapshots can resolve. */
        fun compile(source: VocabularySource): FincrimeVocabulary {
            require(source.schemaVersion == SCHEMA_VERSION) { "unsupported vocabulary schema" }
            requireIdentifier(source.id)
            require(source.revision > 0) { "revision must be positive" }
            requireIdentifier(source.domain)
            requireIdentifier(source.purpose)
            requireText(source.provenance, MAX_PROVENANCE_LENGTH)
            require(source.effectiveUntil == null || source.effectiveUntil > source.effectiveFrom) {
                "effectiveUntil must be after effectiveFrom"
            }
            // Bound the copy itself, then validate the snapshot rather than the caller's mutable list.
            val snapshot = source.entries.take(MAX_ENTRIES + 1)
            require(snapshot.size in 1..MAX_ENTRIES) { "vocabulary must contain 1..$MAX_ENTRIES entries" }
            val entries = snapshot.sortedBy { it.code.value }
            validateEntries(entries)
            val canonical = canonical(source, entries)
            return FincrimeVocabulary(
                VocabularyRef(source.id, source.revision, AuditChain.sha256Hex(canonical)),
                source.domain,
                source.purpose,
                canonical,
                Collections.unmodifiableList(entries),
                source.effectiveFrom,
                source.effectiveUntil,
            )
        }

        private fun validateEntries(entries: List<VocabularyEntry>) {
            require(entries.map { it.code }.toSet().size == entries.size) { "duplicate vocabulary code" }
            entries.forEach {
                requireText(it.label, MAX_LABEL_LENGTH)
                requireText(it.meaning, MAX_MEANING_LENGTH)
                require(it.rank == null || it.rank >= 0) { "rank must be non-negative" }
            }
            val ranks = entries.mapNotNull { it.rank }
            require(ranks.isEmpty() || ranks.size == entries.size) { "scale ranks must cover every code" }
            require(ranks.toSet().size == ranks.size) { "scale ranks must be unique" }
        }

        private fun canonical(source: VocabularySource, entries: List<VocabularyEntry>): String = CanonicalJson.write(
            mapOf(
                "schemaVersion" to source.schemaVersion,
                "id" to source.id,
                "revision" to source.revision,
                "domain" to source.domain,
                "purpose" to source.purpose,
                "provenance" to source.provenance,
                "effectiveFrom" to source.effectiveFrom.toString(),
                "effectiveUntil" to source.effectiveUntil?.toString(),
                "entries" to entries.map {
                    mapOf("code" to it.code.value, "label" to it.label, "meaning" to it.meaning, "rank" to it.rank)
                },
            ),
        )
    }
}

private fun requireIdentifier(value: String) {
    require(value.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) { "invalid vocabulary identifier" }
}

private fun requireText(value: String, maxLength: Int) {
    require(value.length in 1..maxLength && value.isNotBlank()) { "text must be nonblank and bounded" }
    require(value.none { it.isISOControl() }) { "control characters are not allowed" }
    // Reject malformed UTF-16: UTF-8 replacement would otherwise give distinct source strings one hash.
    require(value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value) { "text must be valid Unicode" }
}
