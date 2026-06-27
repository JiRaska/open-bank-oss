// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.docs

import java.io.File
import java.net.URL
import java.util.jar.JarFile

/**
 * Loads `docs/<slug>.md` and `docs/<slug>.<lang>.md` files from the classpath
 * into a per-language in-memory map.
 *
 * File naming convention (Backstage-TechDocs-extended, ISO 639-1 lang code):
 *   docs/README.cs.md           → slug "index",       lang "cs"
 *   docs/README.en.md           → slug "index",       lang "en"
 *   docs/01-overview.cs.md      → slug "01-overview", lang "cs"
 *   docs/01-overview.en.md      → slug "01-overview", lang "en"
 *   docs/04-data.md             → slug "04-data",     lang "" (language-agnostic, served for any lang)
 *
 * Returns: `slug → { lang → content }` where empty-string lang means "default
 * for any language" (a single language-agnostic file). Empty map when the
 * service has no `docs/` resources — no error, no throw.
 *
 * Supports both packaging layouts produced by Quarkus fast-jar:
 *   - file: (exploded, used in tests and dev mode)
 *   - jar:  (production runtime image)
 */
object ClasspathMarkdownLoader {

    /**
     * Returns `slug → { lang → content }` for every `docs/<slug>[.<lang>].md`
     * on the classpath.
     */
    fun load(): Map<String, Map<String, String>> {
        val cl = Thread.currentThread().contextClassLoader
            ?: ClasspathMarkdownLoader::class.java.classLoader
            ?: return emptyMap()

        // Both forms accepted — some classloaders normalise one away.
        val url = cl.getResource("docs/") ?: cl.getResource("docs") ?: return emptyMap()

        val raw: Map<String, String> = when (url.protocol) {
            "file" -> loadFromDirectory(url)
            "jar" -> loadFromJar(url)
            else -> emptyMap()
        }
        return groupByLanguage(raw)
    }

    /**
     * Splits the raw `filename → content` map into `slug → { lang → content }`,
     * applying the `<slug>[.<lang>].md` naming convention.
     *
     * Visible for testing — kept internal to libs but exposed for unit tests
     * that bypass classpath scanning.
     */
    internal fun groupByLanguage(raw: Map<String, String>): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        for ((filename, content) in raw) {
            val baseAndLang = parseSlugAndLang(filename) ?: continue
            val (slug, lang) = baseAndLang
            result.getOrPut(slug) { mutableMapOf() }[lang] = content
        }
        return result
    }

    /**
     * `01-overview.cs.md`  → ("01-overview", "cs")
     * `01-overview.md`     → ("01-overview", "")
     * `README.en.md`       → ("README", "en")
     * `04-data.md`         → ("04-data", "")
     * `not-a-md.txt`       → null
     */
    private fun parseSlugAndLang(filename: String): Pair<String, String>? {
        if (!filename.endsWith(".md")) return null
        val withoutExt = filename.removeSuffix(".md")
        // Detect a 2-letter ISO 639-1 lang suffix preceded by '.', e.g. "name.cs".
        val langMatch = Regex("""^(.+)\.([a-z]{2})$""").matchEntire(withoutExt)
        return if (langMatch != null) {
            val (base, lang) = langMatch.destructured
            base to lang
        } else {
            withoutExt to ""
        }
    }

    private fun loadFromDirectory(url: URL): Map<String, String> {
        val root = runCatching { File(url.toURI()) }.getOrNull() ?: return emptyMap()
        if (!root.isDirectory) return emptyMap()
        return root.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.associate { it.name to it.readText(Charsets.UTF_8) }
            ?: emptyMap()
    }

    private fun loadFromJar(url: URL): Map<String, String> {
        // URL form: "jar:file:/path/to.jar!/docs/" — strip the protocol decorations.
        val external = url.toExternalForm()
        val jarSpec = external.removePrefix("jar:").substringBefore("!/")
        val jarPath = runCatching { java.net.URI(jarSpec).path }.getOrNull()
            ?: return emptyMap()

        val results = mutableMapOf<String, String>()
        JarFile(jarPath).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.startsWith("docs/") || !name.endsWith(".md")) continue
                // Reject sub-directory entries — we only serve a flat docs/ tree.
                val tail = name.substring("docs/".length)
                if (tail.contains('/')) continue
                jar.getInputStream(entry).use { stream ->
                    results[tail] = stream.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return results
    }
}
