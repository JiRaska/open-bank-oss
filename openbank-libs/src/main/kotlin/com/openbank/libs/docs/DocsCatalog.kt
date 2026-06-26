// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.docs

import java.security.MessageDigest

/**
 * Multi-language in-memory catalogue of a service's bundled
 * `docs/<slug>[.<lang>].md` files.
 *
 * The constructor accepts the parsed `slug → { lang → content }` map from
 * [ClasspathMarkdownLoader]. The empty-string lang key denotes a
 * language-agnostic file (e.g. `docs/04-data.md`) which is served for any
 * requested language as a fallback.
 *
 * Slug naming (Backstage-TechDocs-compatible):
 *   - `README` is normalised to slug `index`
 *   - all other `<name>` map to slug `<name>` (e.g. `01-overview`)
 *
 * Per-slug language resolution order, given a requested lang R:
 *   1. exact match: docs[slug][R]
 *   2. default: docs[slug][""] (language-agnostic file)
 *   3. fallback to lang "en"
 *   4. fallback to lang "cs"
 *   5. any first available — guarantees a doc is returned when at least one
 *      language variant exists for the slug
 *
 * The class is `open` only to support test subclassing for ArC; production
 * code never extends it.
 */
open class DocsCatalog(rawDocs: Map<String, Map<String, String>>) {

    /** A single document held in the catalogue, scoped to one language variant. */
    data class Doc(
        val slug: String,
        val lang: String,
        val title: String,
        val content: String,
        /** Strong content hash (sha256 hex) — usable as an ETag. */
        val etag: String,
    )

    /** Index entry returned by the listing endpoint. */
    data class Summary(
        val slug: String,
        val lang: String,
        val availableLanguages: List<String>,
        val title: String,
        val bytes: Int,
        val etag: String,
    )

    /** Catalogue-wide metadata (count of unique slugs, total size, combined hash). */
    data class Meta(
        val count: Int,
        val totalBytes: Long,
        /** sha256 over the concatenated per-(slug,lang) etags, in sorted order. */
        val sha256: String,
    )

    // slug → { lang → Doc }
    private val docs: Map<String, Map<String, Doc>> = rawDocs
        .mapKeys { (rawSlug, _) ->
            if (rawSlug.equals("README", ignoreCase = true)) "index" else rawSlug
        }
        .mapValues { (slug, langMap) ->
            langMap.mapValues { (lang, content) ->
                Doc(
                    slug = slug,
                    lang = lang,
                    title = parseTitle(content) ?: slug,
                    content = content,
                    etag = sha256(content.toByteArray(Charsets.UTF_8)),
                )
            }
        }

    /**
     * Ordered list of all docs in this service, with title/etag for the
     * requested language (falling back per the resolution order above).
     *
     * Each entry advertises which languages exist for that slug via
     * `availableLanguages` so the UI can render a per-doc language switcher.
     */
    fun index(lang: String = DEFAULT_LANG): List<Summary> = docs.entries
        .asSequence()
        .sortedBy { it.key }
        .map { (slug, langMap) ->
            val chosen = resolve(langMap, lang)!! // non-null: slug present implies langMap not empty
            Summary(
                slug = slug,
                lang = chosen.lang,
                availableLanguages = langMap.keys.filter { it.isNotEmpty() }.sorted()
                    .ifEmpty { listOf("") }, // language-agnostic only
                title = chosen.title,
                bytes = chosen.content.toByteArray(Charsets.UTF_8).size,
                etag = chosen.etag,
            )
        }
        .toList()

    /** Returns the document for the given slug+lang, or null when absent. */
    fun read(slug: String, lang: String = DEFAULT_LANG): Doc? {
        val langMap = docs[slug] ?: return null
        return resolve(langMap, lang)
    }

    /** Catalogue-wide metadata for change detection. */
    fun meta(): Meta {
        val flat = docs.entries.flatMap { (slug, langMap) ->
            langMap.entries.map { (lang, doc) -> "$slug/$lang" to doc }
        }.sortedBy { it.first }
        return Meta(
            count = docs.size,
            totalBytes = flat.sumOf { it.second.content.toByteArray(Charsets.UTF_8).size.toLong() },
            sha256 = sha256(flat.joinToString("") { it.second.etag }.toByteArray(Charsets.UTF_8)),
        )
    }

    /** All language codes present across the catalogue (excluding the "any" tag). */
    fun availableLanguages(): List<String> = docs.values
        .flatMap { it.keys }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

    /** True when the service has any docs available. */
    fun isEmpty(): Boolean = docs.isEmpty()

    private fun resolve(langMap: Map<String, Doc>, requested: String): Doc? {
        if (langMap.isEmpty()) return null
        // 1. exact requested language
        langMap[requested]?.let { return it }
        // 2. language-agnostic file (empty-string key)
        langMap[""]?.let { return it }
        // 3-4. en → cs fallbacks
        for (fallback in FALLBACK_CHAIN) {
            if (fallback == requested) continue
            langMap[fallback]?.let { return it }
        }
        // 5. anything
        return langMap.values.firstOrNull()
    }

    companion object {
        const val DEFAULT_LANG = "en"
        private val FALLBACK_CHAIN = listOf("en", "cs")
        private val TITLE_RE = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)

        private fun parseTitle(content: String): String? =
            TITLE_RE.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
