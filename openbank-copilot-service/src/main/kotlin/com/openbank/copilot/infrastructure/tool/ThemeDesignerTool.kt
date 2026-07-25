// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.CopilotTool
import com.openbank.copilot.application.port.out.ToolResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * ADR-0190 theme designer — the model designs the customer's app appearance WITHIN the ThemeSpec
 * token system, never as layout or code. The tool normalizes the model's arguments into a clamped
 * ThemeSpec JSON that rides [ToolResult.themeSpecJson]; the chat loop lifts it onto the reply.
 * The app re-runs its own deterministic validator (contrast repair, semantic invariants) before
 * applying, so this server-side clamp is a courtesy, not the safety boundary.
 */
@ApplicationScoped
class ThemeDesignerTool(private val objectMapper: ObjectMapper) : CopilotTool {

    override val name = NAME
    override val description =
        "Navrhni novy vzhled aplikace (ThemeSpec) podle prani klienta. Zavolej VZDY, kdyz klient " +
            "chce zmenit barvy, tmavy/svetly rezim, pisma, zaobleni, hustotu nebo celkovy vibe " +
            "aplikace. Posli VZDY kompletni specifikaci — vychazej z aktualniho ThemeSpec v kontextu " +
            "a zmen jen to, co klient chce jinak. Barvu zadavej jako HSL (accentH 0-360, accentS 0-1, " +
            "accentL 0-1)."
    override val capability = "theme.design"
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "Kratky nazev motivu, cesky (napr. 'Zapad slunce')"),
            "emoji" to mapOf("type" to "string", "description" to "Jedno emoji vystihujici motiv"),
            "accentH" to mapOf("type" to "number", "description" to "Accent hue 0-360"),
            "accentS" to mapOf("type" to "number", "description" to "Accent saturation 0-1"),
            "accentL" to mapOf("type" to "number", "description" to "Accent lightness 0-1"),
            "mode" to mapOf("type" to "string", "enum" to listOf("light", "dark", "auto")),
            "radiusScale" to mapOf("type" to "number", "description" to "Zaobleni rohu 0.4-2.0"),
            "compact" to mapOf("type" to "boolean", "description" to "Kompaktni hustota"),
            "fontPair" to mapOf("type" to "string", "enum" to listOf("grotesk", "serif", "mono")),
            "fontScale" to mapOf("type" to "number", "description" to "Meritko pisma 0.9-1.1"),
            "surfaceTint" to mapOf("type" to "number", "description" to "Probarveni pozadi akcentem 0-0.1"),
            "decor" to mapOf("type" to "string", "enum" to listOf("none", "aurora", "duotone", "horizon")),
        ),
        "required" to listOf("accentH", "accentS", "accentL", "mode"),
    )

    // The clamp bounds and HSL defaults mirror the app-side ThemeSpecValidator (ADR-0190),
    // which is the authoritative guardrail; this server-side normalization is a courtesy, so
    // the literals live here rather than as a second source-of-truth set of constants.
    @Suppress("MagicNumber")
    override suspend fun call(arguments: JsonNode): ToolResult {
        val h = arguments.path("accentH").asDouble(152.0)
        val s = arguments.path("accentS").asDouble(0.93)
        val l = arguments.path("accentL").asDouble(0.37)
        val mode = arguments.path("mode").asText("light").takeIf { it in MODES } ?: "light"
        val fontPair = arguments.path("fontPair").asText("grotesk").takeIf { it in FONT_PAIRS } ?: "grotesk"
        val decor = arguments.path("decor").asText("none").takeIf { it in DECORS } ?: "none"
        val name = arguments.path("name").asText("").take(60)
        val emoji = arguments.path("emoji").asText("").take(8)

        val spec = objectMapper.createObjectNode().apply {
            put("version", 2)
            put("name", name)
            put("emoji", emoji)
            put("accentH", (((h % 360.0) + 360.0) % 360.0))
            put("accentS", s.coerceIn(0.05, 1.0))
            put("accentL", l.coerceIn(0.15, 0.75))
            put("accentName", "custom")
            put("mode", mode)
            put("radiusScale", arguments.path("radiusScale").asDouble(1.0).coerceIn(0.4, 2.0))
            put("compact", arguments.path("compact").asBoolean(false))
            put("fontPair", fontPair)
            put("fontScale", arguments.path("fontScale").asDouble(1.0).coerceIn(0.9, 1.1))
            put("surfaceTint", arguments.path("surfaceTint").asDouble(0.0).coerceIn(0.0, 0.1))
            put("decor", decor)
        }

        val label = name.ifBlank { "novy vzhled" }
        return ToolResult(
            text = "Motiv \"$label\" je pripraveny a aplikace ho prave prevzala. " +
                "Strucne popis klientovi, co jsi navrhl.",
            themeSpecJson = objectMapper.writeValueAsString(spec),
        )
    }

    companion object {
        const val NAME = "design_theme"
        private val MODES = setOf("light", "dark", "auto")
        private val FONT_PAIRS = setOf("grotesk", "serif", "mono")
        private val DECORS = setOf("none", "aurora", "duotone", "horizon")
    }
}
