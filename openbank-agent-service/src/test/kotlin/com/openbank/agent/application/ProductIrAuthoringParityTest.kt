// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.product.ir.IrJson
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.cli.Obp
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files

/**
 * Gate `product-ir-authoring-parity`, surface half (ADR-0352 §11-§12): one fixture compiled through
 * (a) the `obp` CLI and (b) the MCP `product_compile` handler yields the same canonical bytes and the
 * same revisionHash. (c) the Studio form path does not exist on main yet and is declared pending in
 * the gate script.
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

    @TestFactory
    fun `CLI and MCP compile the same fixture to identical canonical bytes and hash`(): List<DynamicTest> =
        ReferenceProducts.all.map { product ->
            DynamicTest.dynamicTest(product.name) {
                // Author the fixture as non-canonical JSON (pretty-printed, keys reordered by Jackson),
                // so both surfaces have to canonicalise rather than echo.
                val fixture = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(mapper.readTree(product.canonicalJson()))
                val file = Files.createTempFile("parity-", ".json")
                Files.writeString(file, fixture)

                val out = StringBuilder()
                val code = Obp.run(listOf("compile", file.toString()), out, StringBuilder())
                assertThat(code).isEqualTo(Obp.EXIT_OK)
                val cliLines = out.lines().filter { it.isNotBlank() }
                val cliBytes = cliLines.first()
                val cliHash = (IrJson.parse(cliLines.last()) as Map<*, *>)["revisionHash"]

                val mcp = registry.call(
                    "product_compile",
                    mapper.createObjectNode().put("ir", fixture),
                    "product-author",
                )
                assertThat(mcp.isError).isFalse()
                val mcpTree = IrJson.parse(mcp.content.first().text) as Map<*, *>
                val mcpBytes = CanonicalJson.write(mcpTree["ir"])

                assertThat(mcpBytes).isEqualTo(cliBytes)
                assertThat(mcpTree["revisionHash"]).isEqualTo(cliHash)
                assertThat(cliHash).isEqualTo(product.compile().revisionHash)
                Files.delete(file)
            }
        }
}
