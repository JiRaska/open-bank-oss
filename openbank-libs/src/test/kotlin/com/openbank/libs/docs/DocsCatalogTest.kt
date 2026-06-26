// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.docs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocsCatalogTest {

    @Test
    fun `empty input produces empty catalogue`() {
        val cat = DocsCatalog(emptyMap())
        assertThat(cat.isEmpty()).isTrue()
        assertThat(cat.index("en")).isEmpty()
        assertThat(cat.read("anything")).isNull()
        assertThat(cat.availableLanguages()).isEmpty()
        val meta = cat.meta()
        assertThat(meta.count).isZero
        assertThat(meta.totalBytes).isZero
    }

    @Test
    fun `README is normalised to slug index`() {
        val cat = DocsCatalog(
            mapOf(
                "README" to mapOf("cs" to "# Účet Service\n…"),
            ),
        )
        assertThat(cat.index("cs").map { it.slug }).containsExactly("index")
        val doc = cat.read("index", "cs")
        assertThat(doc).isNotNull
        assertThat(doc!!.title).isEqualTo("Účet Service")
        assertThat(doc.lang).isEqualTo("cs")
    }

    @Test
    fun `title is parsed per-language from first H1`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf(
                    "cs" to "# Přehled\nčesky",
                    "en" to "# Overview\nenglish",
                ),
            ),
        )
        assertThat(cat.read("01-overview", "cs")!!.title).isEqualTo("Přehled")
        assertThat(cat.read("01-overview", "en")!!.title).isEqualTo("Overview")
    }

    @Test
    fun `requested lang wins over fallbacks`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf("cs" to "# CS", "en" to "# EN"),
            ),
        )
        assertThat(cat.read("01-overview", "cs")!!.content).isEqualTo("# CS")
        assertThat(cat.read("01-overview", "en")!!.content).isEqualTo("# EN")
    }

    @Test
    fun `missing lang falls back to language-agnostic file`() {
        val cat = DocsCatalog(
            mapOf(
                "04-data" to mapOf("" to "# Data (no translation)"),
            ),
        )
        val doc = cat.read("04-data", "fr")
        assertThat(doc).isNotNull
        assertThat(doc!!.content).isEqualTo("# Data (no translation)")
        assertThat(doc.lang).isEmpty()
    }

    @Test
    fun `missing lang falls back to en before cs`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf("en" to "# EN", "cs" to "# CS"),
            ),
        )
        val doc = cat.read("01-overview", "fr")
        assertThat(doc!!.lang).isEqualTo("en")
    }

    @Test
    fun `missing lang falls back to cs when en absent`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf("cs" to "# CS only"),
            ),
        )
        val doc = cat.read("01-overview", "fr")
        assertThat(doc!!.lang).isEqualTo("cs")
    }

    @Test
    fun `index reports all available languages per doc`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf("cs" to "# CS", "en" to "# EN", "de" to "# DE"),
                "04-data" to mapOf("" to "# Data"),
            ),
        )
        val byslug = cat.index("en").associateBy { it.slug }
        assertThat(byslug["01-overview"]!!.availableLanguages).containsExactly("cs", "de", "en")
        assertThat(byslug["04-data"]!!.availableLanguages).containsExactly("") // language-agnostic only
    }

    @Test
    fun `availableLanguages reports the union across the catalogue`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf("cs" to "# CS", "en" to "# EN"),
                "02-architecture" to mapOf("en" to "# EN", "de" to "# DE"),
                "04-data" to mapOf("" to "# any"),
            ),
        )
        assertThat(cat.availableLanguages()).containsExactly("cs", "de", "en")
    }

    @Test
    fun `etag changes with content not with slug`() {
        val a = DocsCatalog(mapOf("x" to mapOf("en" to "# Title\nbody1")))
        val b = DocsCatalog(mapOf("x" to mapOf("en" to "# Title\nbody1")))
        val c = DocsCatalog(mapOf("x" to mapOf("en" to "# Title\nbody2")))
        assertThat(a.read("x", "en")!!.etag).isEqualTo(b.read("x", "en")!!.etag)
        assertThat(a.read("x", "en")!!.etag).isNotEqualTo(c.read("x", "en")!!.etag)
        assertThat(a.read("x", "en")!!.etag).hasSize(64).matches("[0-9a-f]+")
    }

    @Test
    fun `meta count is unique slug count and aggregates bytes across langs`() {
        val cat = DocsCatalog(
            mapOf(
                "01-overview" to mapOf("cs" to "# CS\nčesky", "en" to "# EN\neng"),
            ),
        )
        val meta = cat.meta()
        assertThat(meta.count).isEqualTo(1)
        // CS "# CS\nčesky" = '#'+' '+'C'+'S'+'\n'+'č'(2)+'e'+'s'+'k'+'y' = 11 bytes UTF-8
        // EN "# EN\neng" = 8 bytes
        assertThat(meta.totalBytes).isEqualTo(19L)
        assertThat(meta.sha256).hasSize(64)
    }

    @Test
    fun `index sorted by slug ascending`() {
        val cat = DocsCatalog(
            mapOf(
                "05-operations" to mapOf("en" to "# Ops"),
                "01-overview" to mapOf("en" to "# Ov"),
                "03-api" to mapOf("en" to "# API"),
            ),
        )
        assertThat(cat.index("en").map { it.slug })
            .containsExactly("01-overview", "03-api", "05-operations")
    }

    @Test
    fun `loader splits filename on lang suffix correctly`() {
        val raw = mapOf(
            "01-overview.cs.md" to "# CS",
            "01-overview.en.md" to "# EN",
            "04-data.md" to "# any",
            "README.cs.md" to "# CS readme",
            "README.en.md" to "# EN readme",
            "not-markdown.txt" to "ignored",
        )
        val grouped = ClasspathMarkdownLoader.groupByLanguage(raw)
        assertThat(grouped).containsOnlyKeys("01-overview", "04-data", "README")
        assertThat(grouped["01-overview"]).containsOnlyKeys("cs", "en")
        assertThat(grouped["04-data"]).containsOnlyKeys("")
        assertThat(grouped["README"]).containsOnlyKeys("cs", "en")
    }
}
