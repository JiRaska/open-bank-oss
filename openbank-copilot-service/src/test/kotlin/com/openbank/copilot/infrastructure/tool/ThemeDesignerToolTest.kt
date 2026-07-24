// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThemeDesignerToolTest {

    private val mapper = ObjectMapper()
    private val tool = ThemeDesignerTool(mapper)

    @Test
    fun `normalizes a full spec and rides it on themeSpecJson`(): Unit = runBlocking {
        val args = mapper.createObjectNode().apply {
            put("name", "Západ slunce")
            put("emoji", "🌅")
            put("accentH", 21.0)
            put("accentS", 0.8)
            put("accentL", 0.5)
            put("mode", "dark")
            put("radiusScale", 1.4)
            put("fontPair", "serif")
            put("decor", "aurora")
        }

        val result = tool.call(args)

        assertThat(result.isError).isFalse()
        assertThat(result.themeSpecJson).isNotNull()
        val spec = mapper.readTree(result.themeSpecJson)
        assertThat(spec.get("version").asInt()).isEqualTo(2)
        assertThat(spec.get("name").asText()).isEqualTo("Západ slunce")
        assertThat(spec.get("accentH").asDouble()).isEqualTo(21.0)
        assertThat(spec.get("accentName").asText()).isEqualTo("custom")
        assertThat(spec.get("mode").asText()).isEqualTo("dark")
        assertThat(spec.get("fontPair").asText()).isEqualTo("serif")
        assertThat(spec.get("decor").asText()).isEqualTo("aurora")
        assertThat(result.text).contains("Západ slunce")
    }

    @Test
    fun `clamps out-of-range values and snaps unknown enums`(): Unit = runBlocking {
        val args = mapper.createObjectNode().apply {
            put("accentH", 725.0)
            put("accentS", 4.0)
            put("accentL", 0.99)
            put("mode", "disco")
            put("radiusScale", 9.0)
            put("fontPair", "comic-sans")
            put("fontScale", 3.0)
            put("surfaceTint", 1.0)
            put("decor", "lasers")
        }

        val spec = mapper.readTree(tool.call(args).themeSpecJson)

        assertThat(spec.get("accentH").asDouble()).isEqualTo(5.0)
        assertThat(spec.get("accentS").asDouble()).isEqualTo(1.0)
        assertThat(spec.get("accentL").asDouble()).isEqualTo(0.75)
        assertThat(spec.get("mode").asText()).isEqualTo("light")
        assertThat(spec.get("radiusScale").asDouble()).isEqualTo(2.0)
        assertThat(spec.get("fontPair").asText()).isEqualTo("grotesk")
        assertThat(spec.get("fontScale").asDouble()).isEqualTo(1.1)
        assertThat(spec.get("surfaceTint").asDouble()).isEqualTo(0.1)
        assertThat(spec.get("decor").asText()).isEqualTo("none")
    }

    @Test
    fun `defaults apply when only required fields arrive`(): Unit = runBlocking {
        val args = mapper.createObjectNode().apply {
            put("accentH", 200.0)
            put("accentS", 0.6)
            put("accentL", 0.45)
            put("mode", "light")
        }

        val spec = mapper.readTree(tool.call(args).themeSpecJson)

        assertThat(spec.get("radiusScale").asDouble()).isEqualTo(1.0)
        assertThat(spec.get("compact").asBoolean()).isFalse()
        assertThat(spec.get("fontPair").asText()).isEqualTo("grotesk")
        assertThat(spec.get("decor").asText()).isEqualTo("none")
        assertThat(spec.get("name").asText()).isEmpty()
    }
}
