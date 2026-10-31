// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.product.toolkit.ProductToolkit
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.cli.Obp
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gate `product-ir-authoring-parity`, surface half (ADR-0352 §11-§12). For one fixture per reference
 * product, every shared operation — lint, compile, simulate, explain, and their failure outcomes —
 * gives the SAME raw bytes through (a) the `obp` CLI (its stdout on success, its stderr line on
 * failure) and (b) the MCP handler's text, and the CLI exits non-zero exactly when MCP says
 * `isError`. (c) the Studio form path does not exist on main yet and is declared pending in the gate
 * script.
 */
class ProductIrAuthoringParityTest {

    private val mapper = ObjectMapper()
    private val registry = McpToolRegistry().also {
        it.objectMapper = mapper
        it.auditPublisher = object : AuditEventPublisher {
            override suspend fun publish(event: AuditEvent) = Unit
        }
        it.proposals = mockk()
        it.charters = CharterRegistry().also { c -> c.config = mockk { every { charters() } returns emptyList() } }
    }
    private val portfolio = Path.of("..", "openbank-products", "portfolios", "savings-sample.json").toAbsolutePath()

    /** Every object's keys in REVERSE canonical order: Jackson preserves the order it is given. */
    private fun reversed(node: Any?): Any? = when (node) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
            node.keys.map { it as String }.sortedDescending().forEach { put(it, reversed(node[it])) }
        }
        is List<*> -> node.map { reversed(it) }
        else -> node
    }

    /** One CLI run: the single canonical line it printed (stdout on success, stderr on failure). */
    private fun cli(vararg args: String): Pair<Int, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = Obp.run(args.toList(), out, err)
        val stream = if (code == Obp.EXIT_OK) out else err
        return code to stream.lines().single { it.isNotBlank() }
    }

    private fun mcp(tool: String, fill: ObjectNode.() -> Unit): Pair<Boolean, String> {
        val result = registry.call(tool, mapper.createObjectNode().apply(fill), "product-author")
        return result.isError to result.content.single().text
    }

    private fun same(label: String, cli: Pair<Int, String>, mcp: Pair<Boolean, String>) {
        assertThat(mcp.second).describedAs("$label: raw bytes").isEqualTo(cli.second)
        assertThat(mcp.first).describedAs("$label: MCP isError iff CLI exits non-zero").isEqualTo(cli.first != 0)
    }

    @TestFactory
    fun `CLI and MCP give identical bytes and failure status for every shared operation`(): List<DynamicTest> =
        ReferenceProducts.all.map { product ->
            DynamicTest.dynamicTest(product.name) {
                val canonical = product.canonicalJson()
                val fixture = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(reversed(mapper.readValue(canonical, Map::class.java)))
                assertThat(fixture).isNotEqualTo(canonical)
                val broken = fixture.replace("\"irVersion\" : 1", "\"irVersion\" : 99")
                assertThat(broken).isNotEqualTo(fixture)
                val dir = Files.createTempDirectory("parity-")
                val file = dir.resolve("ir.json").also { Files.writeString(it, fixture) }
                val bad = dir.resolve("bad.json").also { Files.writeString(it, broken) }
                val ruleId = ProductToolkit.ruleIdPaths(product.compile()).keys.first()

                val mcpCompile = mcp("product_compile") { put("ir", fixture) }
                same("compile --json", cli("compile", file.toString(), "--json"), mcpCompile)
                // Default `obp compile <file>` prints the raw canonical IR: exactly the MCP report's `ir` slice.
                val raw = StringBuilder().also { Obp.run(listOf("compile", file.toString()), it, StringBuilder()) }
                    .toString().trimEnd()
                assertThat(mcpCompile.second)
                    .isEqualTo("{\"ir\":$raw,\"ok\":true,\"revisionHash\":\"${product.compile().revisionHash}\"}")
                same("lint", cli("lint", file.toString()), mcp("product_lint") { put("ir", fixture) })
                same("lint invalid", cli("lint", bad.toString()), mcp("product_lint") { put("ir", broken) })
                same(
                    "compile invalid",
                    cli("compile", bad.toString(), "--json"),
                    mcp("product_compile") {
                        put("ir", broken)
                    },
                )
                same(
                    "simulate",
                    cli("simulate", file.toString(), "--portfolio", portfolio.toString()),
                    mcp("product_simulate") {
                        put("ir", fixture)
                        put("portfolio", Files.readString(portfolio))
                    },
                )
                same(
                    "explain",
                    cli("explain", file.toString(), "--rule", ruleId),
                    mcp("product_explain") {
                        put("ir", fixture)
                        put("ruleId", ruleId)
                    },
                )
                same(
                    "explain unknown rule",
                    cli("explain", file.toString(), "--rule", "no.such.rule"),
                    mcp("product_explain") {
                        put("ir", fixture)
                        put("ruleId", "no.such.rule")
                    },
                )
                dir.toFile().deleteRecursively()
            }
        }
}
