// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductIrValidationException
import com.openbank.libs.product.toolkit.ProductToolkit
import java.time.Instant

/**
 * The MCP product-authoring tools (ADR-0352 §12): `product_lint`, `product_compile`,
 * `product_simulate`, `product_explain` (read tier) and `product_propose` (write_proposal tier).
 *
 * Each handler is a thin argument adapter over [ProductToolkit] — the exact functions the `obp` CLI
 * calls — and returns the toolkit's canonical JSON text unchanged, so the CLI and MCP surfaces
 * produce identical bytes (gate `product-ir-authoring-parity`). All of it runs in-process: no
 * downstream call, no key, no catalog write. `product_propose` only computes the DRAFT-revision plan;
 * [McpToolRegistry] records it as a HITL proposal a human disposes.
 *
 * There is deliberately no publish, sign or approve tool (ADR-0259: an agent never publishes; the
 * signature belongs to the catalog publish path, ADR-0352 §4). [FORBIDDEN_VERBS] is asserted
 * against the whole registered catalog by a test, so adding one fails the build.
 */
object ProductAuthoringTools {

    const val LINT = "product_lint"
    const val COMPILE = "product_compile"
    const val SIMULATE = "product_simulate"
    const val EXPLAIN = "product_explain"
    const val PROPOSE = "product_propose"

    /** Read-only tools: they compute over the arguments and nothing else. */
    val READ_TOOLS = listOf(LINT, COMPILE, SIMULATE, EXPLAIN)

    /** Wire name -> charter capability (agents.yaml `tool_tiers`). */
    val CAPABILITIES = mapOf(
        LINT to "product.lint",
        COMPILE to "product.compile",
        SIMULATE to "product.simulate",
        EXPLAIN to "product.explain",
        PROPOSE to "product.propose",
    )

    /** No registered MCP tool may carry one of these verbs — by construction, not by policy. */
    val FORBIDDEN_VERBS = Regex("(publish|sign|approve)", RegexOption.IGNORE_CASE)

    /** Bound on any JSON-text argument; an IR v1 document is a few KiB. */
    const val MAX_DOCUMENT_CHARS = 262_144

    fun lint(args: JsonNode): String = ProductToolkit.write(ProductToolkit.lint(document(args, "ir")))

    /** Canonical IR bytes and hash; an invalid document answers with the same report `lint` gives. */
    fun compile(args: JsonNode): String = try {
        ProductToolkit.write(ProductToolkit.compile(document(args, "ir")).tree())
    } catch (_: ProductIrValidationException) {
        lint(args)
    }

    fun simulate(args: JsonNode): String = ProductToolkit.write(
        ProductToolkit.simulate(
            ProductIrParser.parse(document(args, "ir")),
            document(args, "portfolio"),
            FinanceProductArithmetic,
            optionalInstant(args, "from"),
            optionalInstant(args, "to"),
        ),
    )

    fun explain(args: JsonNode): String {
        val rev = ProductIrParser.parse(document(args, "ir"))
        val ruleId = args["ruleId"]?.asText()?.takeIf { it.isNotBlank() }
        val instruction = args["instruction"]?.asText()?.takeIf { it.isNotBlank() }
        val report = when {
            ruleId != null -> ProductToolkit.explainRule(rev, ruleId)
            instruction != null -> ProductToolkit.explainInstruction(rev, bounded(instruction, "instruction"))
            else -> throw IllegalArgumentException("Required field 'ruleId' or 'instruction' is missing or blank")
        }
        return ProductToolkit.write(report)
    }

    /** The DRAFT-revision plan `obp propose` prints. Never executed here. */
    fun proposalPlan(args: JsonNode): Map<String, Any?> = ProductToolkit.proposalPlan(
        ProductIrParser.parse(document(args, "ir")),
        required(args, "offeringId"),
        mapOf(
            "id" to required(args, "schemaId"),
            "version" to (
                required(args, "schemaVersion").toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("schemaVersion must be a positive integer")
                ),
        ),
    )

    private fun document(args: JsonNode, field: String): String = bounded(required(args, field), field)

    private fun bounded(text: String, field: String): String {
        require(text.length <= MAX_DOCUMENT_CHARS) { "'$field' exceeds $MAX_DOCUMENT_CHARS characters" }
        return text
    }

    private fun required(args: JsonNode, field: String): String =
        args[field]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Required field '$field' is missing or blank")

    private fun optionalInstant(args: JsonNode, field: String): Instant? =
        args[field]?.asText()?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrElse { throw IllegalArgumentException("'$field' must be ISO-8601") }
        }
}
