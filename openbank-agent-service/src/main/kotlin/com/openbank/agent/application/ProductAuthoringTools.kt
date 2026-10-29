// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.audit.CanonicalJson
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
    /**
     * No registered MCP tool may carry one of these verbs — by construction, not by policy. Matched as
     * a whole name segment (`_`, `.`, `-` separated), so `assign_owner` or `design_review` are not hits.
     */
    val FORBIDDEN_VERBS = Regex("(?:^|[._-])(publish|sign|approve)(?:$|[._-])", RegexOption.IGNORE_CASE)

    /** Bound on any JSON-text argument; an IR v1 document is a few KiB. */
    const val MAX_DOCUMENT_CHARS = 262_144

    /** Bound on the free-text rationale a proposal carries into the HITL queue. */
    const val MAX_RATIONALE_CHARS = 4_096

    /**
     * What a product tool hands back: the toolkit's canonical JSON, byte-identical to what `obp`
     * prints, and [ok] — false exactly when `obp` would exit non-zero for the same input, so the
     * MCP result carries `isError = true` and an agent that reads only `isError` cannot mistake a
     * failed lint or an unknown rule for success.
     */
    data class Outcome(val text: String, val ok: Boolean)

    fun lint(args: JsonNode): Outcome = outcome { ProductToolkit.lint(required(args, "ir")) }

    fun compile(args: JsonNode): Outcome = outcome { ProductToolkit.compile(required(args, "ir")).tree() }

    fun simulate(args: JsonNode): Outcome = outcome {
        ProductToolkit.simulate(
            ProductIrParser.parse(required(args, "ir")),
            required(args, "portfolio"),
            FinanceProductArithmetic,
            optionalInstant(args, "from"),
            optionalInstant(args, "to"),
        )
    }

    fun explain(args: JsonNode): Outcome = outcome {
        val rev = ProductIrParser.parse(required(args, "ir"))
        val ruleId = args["ruleId"]?.asText()?.takeIf { it.isNotBlank() }
        val instruction = args["instruction"]?.asText()?.takeIf { it.isNotBlank() }
        when {
            ruleId != null -> ProductToolkit.explainRule(rev, ruleId)
            instruction != null -> ProductToolkit.explainInstruction(rev, bounded(instruction, "instruction"))
            else -> throw IllegalArgumentException("Required field 'ruleId' or 'instruction' is missing or blank")
        }
    }

    /** The rationale, bounded before it reaches the HITL queue. */
    fun rationale(args: JsonNode): String = required(args, "rationale", MAX_RATIONALE_CHARS)

    /**
     * An invalid IR document answers with the same `{ok:false, problems}` report `obp` prints on
     * stderr; any other argument error propagates as [IllegalArgumentException] (invalid parameters).
     */
    private fun outcome(report: () -> Map<String, Any?>): Outcome {
        val tree = try {
            report()
        } catch (e: ProductIrValidationException) {
            ProductToolkit.problems(e)
        }
        return Outcome(CanonicalJson.write(tree), tree["ok"] == true)
    }

    /** The DRAFT-revision plan `obp propose` prints. Never executed here. */
    fun proposalPlan(args: JsonNode): Map<String, Any?> = ProductToolkit.proposalPlan(
        ProductIrParser.parse(required(args, "ir")),
        required(args, "offeringId"),
        mapOf(
            "id" to required(args, "schemaId"),
            "version" to (
                required(args, "schemaVersion").toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("schemaVersion must be a positive integer")
                ),
        ),
    )

    private fun bounded(text: String, field: String, max: Int = MAX_DOCUMENT_CHARS): String {
        require(text.length <= max) { "'$field' exceeds $max characters" }
        return text
    }

    /** A required, non-blank text argument, bounded to [max] characters. */
    private fun required(args: JsonNode, field: String, max: Int = MAX_DOCUMENT_CHARS): String {
        val text = args[field]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Required field '$field' is missing or blank")
        return bounded(text, field, max)
    }

    private fun optionalInstant(args: JsonNode, field: String): Instant? =
        args[field]?.asText()?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrElse { throw IllegalArgumentException("'$field' must be ISO-8601") }
        }
}
