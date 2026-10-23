// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One sub-command per function; the dispatcher is the natural shape of a CLI.
@file:Suppress("TooManyFunctions")

package com.openbank.products.cli

import com.openbank.libs.audit.CanonicalJson
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductIrValidationException
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.testing.product.GoldenScenarioRunner
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.dsl.ProductDefinition
import com.openbank.products.golden.ProductGoldens
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `obp` — the Product as Code developer surface (ADR-0352 §11.1). Fully offline: every command runs
 * the one pure interpreter from openbank-libs-domain against files on disk and prints canonical
 * JSON (RFC 8785 key order) on stdout, so output is diffable and machine-readable.
 *
 * ```
 * obp lint     <ir.json | product-name>                 validate; print productCode + revisionHash
 * obp compile  <product-name|all> [--out DIR] [--check] DSL -> canonical IR JSON; --check compares to DIR/<name>.json
 * obp test                                               golden scenarios through the shared runner
 * obp diff     <revA.json> <revB.json> --behavioural --portfolio <fixture.json>
 *                                                        replay a portfolio under both; posting deltas per ruleId
 * obp explain  <ruleId> [--product <name>]              ruleId -> DSL source location
 * obp sign | obp propose                                 refused here (publish-path / catalog identity only)
 * ```
 *
 * `simulate` (ADR-0352 §11.1) is phase 4 together with `ProductReplayScenario`; `diff --behavioural`
 * is its two-revision special case and ships now because the repricing path (§11.4) needs it.
 */
object Obp {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_REFUSED = 2
    const val EXIT_USAGE = 64

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args.toList(), System.out, System.err))
    }

    /** Runs one invocation; the testable entry point. */
    @Suppress("ReturnCount")
    fun run(args: List<String>, out: Appendable, err: Appendable): Int {
        if (args.isEmpty()) return usage(err)
        return try {
            when (args[0]) {
                "lint" -> lint(args.drop(1), out)
                "compile" -> compile(args.drop(1), out, err)
                "test" -> test(out)
                "diff" -> diff(args.drop(1), out, err)
                "explain" -> explain(args.drop(1), out, err)
                "sign" -> refuse(
                    "sign",
                    "the publish-path signature is produced by product-catalog's publish-ir with a key only that " +
                        "path holds (ADR-0352 §4); a developer, an agent or a CLI never signs",
                    err,
                )
                "propose" -> refuse(
                    "propose",
                    "opening the PR and the DRAFT revision through the catalog v2 API is phase 5 of ADR-0352; " +
                        "author the IR here and open the PR yourself (it never publishes)",
                    err,
                )
                else -> usage(err)
            }
        } catch (e: ProductIrValidationException) {
            emit(
                err,
                mapOf(
                    "ok" to false,
                    "problems" to e.problems.map { mapOf("path" to it.path, "message" to it.message) },
                ),
            )
            EXIT_FAILURE
        } catch (e: CliUsageException) {
            err.appendLine("obp: ${e.message}")
            EXIT_USAGE
        }
    }

    private fun usage(err: Appendable): Int {
        err.appendLine(
            "usage: obp lint <ir.json|product> | compile <product|all> [--out DIR] [--check] | test | " +
                "diff <revA.json> <revB.json> --behavioural --portfolio <fixture.json> | " +
                "explain <ruleId> [--product <name>] | sign | propose",
        )
        return EXIT_USAGE
    }

    private fun refuse(command: String, why: String, err: Appendable): Int {
        emit(err, mapOf("ok" to false, "command" to command, "refused" to why))
        return EXIT_REFUSED
    }

    // --- lint ---------------------------------------------------------------------------------

    private fun lint(args: List<String>, out: Appendable): Int {
        val target = args.firstOrNull() ?: throw CliUsageException("lint needs an IR file or a product name")
        val revision = loadRevision(target)
        emit(
            out,
            mapOf(
                "ok" to true,
                "productCode" to revision.productCode,
                "irVersion" to revision.irVersion,
                "revisionHash" to revision.revisionHash,
            ),
        )
        return EXIT_OK
    }

    /** A path to an IR document, or the name of a reference product compiled from the DSL. */
    private fun loadRevision(target: String): ProductRevisionIr {
        val path = Path.of(target)
        if (Files.isRegularFile(path)) return ProductIrParser.parse(Files.readString(path))
        return ReferenceProducts.byName(target)?.compile()
            ?: throw CliUsageException(
                "'$target' is neither a file nor a reference product (${ReferenceProducts.all.map {
                    it.name
                }})",
            )
    }

    // --- compile ------------------------------------------------------------------------------

    private fun compile(args: List<String>, out: Appendable, err: Appendable): Int {
        val name = args.firstOrNull() ?: usageError("compile needs a product name or 'all'")
        val outDir = option(args, "--out")?.let { Path.of(it) }
        val check = "--check" in args
        val products = if (name == "all") {
            ReferenceProducts.all
        } else {
            listOf(ReferenceProducts.byName(name) ?: usageError("unknown product '$name'"))
        }
        val results = products.map { p -> compileOne(p, outDir, check, out) }
        val drift = results.count { it["status"] == "drift" || it["status"] == "missing" }
        emit(out, mapOf("ok" to (drift == 0), "compiled" to results))
        if (drift > 0) {
            err.appendLine(
                "obp compile --check: $drift committed IR file(s) differ from the DSL; run `obp compile all --out ir`",
            )
        }
        return if (drift == 0) EXIT_OK else EXIT_FAILURE
    }

    private fun compileOne(p: ProductDefinition, outDir: Path?, check: Boolean, out: Appendable): Map<String, Any?> {
        val canonical = p.canonicalJson()
        val rev = p.compile()
        val file = (outDir ?: Path.of("ir")).resolve("${p.name}.json")
        val committed = if (Files.isRegularFile(file)) Files.readString(file).trimEnd() else null
        val status = when {
            check -> if (committed == null) {
                "missing"
            } else if (committed != canonical) {
                "drift"
            } else {
                "identical"
            }
            outDir != null -> {
                Files.createDirectories(outDir)
                Files.writeString(file, canonical + "\n")
                "written"
            }
            else -> {
                out.appendLine(canonical)
                "printed"
            }
        }
        return mapOf(
            "product" to p.name,
            "productCode" to rev.productCode,
            "revisionHash" to rev.revisionHash,
            "file" to file.toString(),
            "status" to status,
        )
    }

    // --- test ---------------------------------------------------------------------------------

    private fun test(out: Appendable): Int {
        val runner = GoldenScenarioRunner()
        val results = ProductGoldens.names().map { name ->
            val scenario = ProductGoldens.load(name)
            val failure = runCatching { runner.assertPasses(scenario) }.exceptionOrNull()
            mapOf(
                "scenario" to name,
                "steps" to scenario.steps.size,
                "passed" to (failure == null),
                "failure" to failure?.message,
            )
        }
        val failed = results.count { it["passed"] == false }
        emit(out, mapOf("ok" to (failed == 0), "scenarios" to results.size, "failed" to failed, "results" to results))
        return if (failed == 0) EXIT_OK else EXIT_FAILURE
    }

    // --- diff --behavioural --------------------------------------------------------------------

    private fun diff(args: List<String>, out: Appendable, err: Appendable): Int {
        val files = args.filter { !it.startsWith("--") && args.getOrNull(args.indexOf(it) - 1) != "--portfolio" }
        val portfolio = option(args, "--portfolio")
        val problem = when {
            "--behavioural" !in args -> "diff supports --behavioural only (structural diff is the catalog's)"
            files.size != 2 -> "diff needs exactly two IR files"
            portfolio == null -> "diff --behavioural needs --portfolio <fixture.json>"
            else -> null
        }
        if (problem != null) usageError(problem)
        val a = loadRevision(files[0])
        val b = loadRevision(files[1])
        val report = BehaviouralDiff.run(a, b, Files.readString(Path.of(portfolio!!)))
        emit(out, report)
        if (report["changedRules"] !=
            0
        ) {
            err.appendLine("obp diff: ${report["changedRules"]} rule(s) post differently; see per-rule deltas")
        }
        return EXIT_OK
    }

    // --- explain ------------------------------------------------------------------------------

    private fun explain(args: List<String>, out: Appendable, err: Appendable): Int {
        val ruleId =
            args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: throw CliUsageException("explain needs a ruleId")
        val productName = option(args, "--product")
        val candidates = if (productName !=
            null
        ) {
            listOfNotNull(ReferenceProducts.byName(productName))
        } else {
            ReferenceProducts.all
        }
        val hits = candidates.mapNotNull { p ->
            p.sources[ruleId]?.let { loc ->
                mapOf(
                    "product" to p.name,
                    "source" to loc.toString(),
                )
            }
        }
        if (hits.isEmpty()) {
            emit(
                err,
                mapOf("ok" to false, "ruleId" to ruleId, "message" to "no reference product declares this ruleId"),
            )
            return EXIT_FAILURE
        }
        emit(out, mapOf("ok" to true, "ruleId" to ruleId, "declaredIn" to hits))
        return EXIT_OK
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun usageError(message: String): Nothing = throw CliUsageException(message)

    private fun option(args: List<String>, name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun emit(to: Appendable, tree: Map<String, Any?>) {
        to.appendLine(CanonicalJson.write(tree))
    }

    /** Re-reads canonical JSON text as a tree, for embedding a revision in a scenario document. */
    internal fun revisionTree(rev: ProductRevisionIr): Any? = IrJson.parse(ProductIrCanonical.canonicalJson(rev))
}

class CliUsageException(message: String) : RuntimeException(message)
