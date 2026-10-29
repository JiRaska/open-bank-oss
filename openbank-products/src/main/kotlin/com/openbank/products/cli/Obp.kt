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
import com.openbank.libs.product.toolkit.ProductToolkit
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.RevisionIdentity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import com.openbank.libs.testing.product.GoldenScenarioRunner
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.dsl.ProductDefinition
import com.openbank.products.golden.ProductGoldens
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `obp` — the Product as Code developer surface (ADR-0352 §11.1). Every command except
 * `propose --execute` is fully offline: it runs [ProductToolkit] — the same functions the
 * agent-service MCP product tools call — against files on disk and prints canonical JSON
 * (RFC 8785 key order) on stdout, so output is diffable, machine-readable and byte-identical between
 * runs.
 *
 * ```
 * obp lint     <ir.json|product>                          schema + run-limits + ruleId uniqueness
 * obp compile  <product|ir.json|all> [--out DIR] [--check] canonical IR JSON + revisionHash
 * obp test                                                 golden scenarios through the shared runner
 * obp simulate <product|ir.json> --portfolio <fixture.json> [--from ISO] [--to ISO]
 *                                                          replay a portfolio; canonical instructions
 * obp explain  <product|ir.json> --rule <ruleId>           ruleId -> IR path + DSL source location
 * obp explain  <product|ir.json> --instruction <json>      an emitted instruction -> its rule
 * obp explain  <ruleId> [--product <name>]                 ruleId -> DSL source location (reference products)
 * obp sign     <ir.json|product> --key-ref test:<name> --revision-id <uuid> --revision-number <n>
 *                                                          identity-bound envelope signature, OFFLINE TEST KEY only
 * obp propose  <product|ir.json> --offering <uuid> --schema-id <id> --schema-version <n>
 *              [--execute --catalog-url URL]              plan (or create) a DRAFT revision; never publishes
 * obp diff     <revA.json> <revB.json> --behavioural --portfolio <fixture.json>
 *                                                          per-rule posting deltas over a portfolio
 * ```
 *
 * Exit codes: [EXIT_OK] 0 success · [EXIT_FAILURE] 1 the subject is invalid / a check failed ·
 * [EXIT_REFUSED] 2 the command refuses (a non-test key, a failed execute) · [EXIT_USAGE] 64 bad
 * arguments.
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
                "simulate" -> simulate(args.drop(1), out)
                "sign" -> sign(args.drop(1), out, err)
                "propose" -> propose(args.drop(1), out, err)
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
                "simulate <product> --portfolio <fixture.json> [--from ISO] [--to ISO] | " +
                "explain <product> --rule <ruleId> | explain <product> --instruction <json> | " +
                "explain <ruleId> [--product <name>] | " +
                "sign <ir> --key-ref test:<name> --revision-id <uuid> --revision-number <n> | " +
                "propose <product> --offering <uuid> --schema-id <id> --schema-version <n> [--execute --catalog-url URL]",
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
        emit(out, ProductToolkit.lintOk(loadRevision(target)))
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
        if (ReferenceProducts.byName(name) == null && name != "all" && Files.isRegularFile(Path.of(name))) {
            val compiled = ProductToolkit.compile(Files.readString(Path.of(name)))
            out.appendLine(compiled.canonicalJson)
            emit(out, mapOf("ok" to true, "revisionHash" to compiled.revisionHash, "status" to "printed"))
            return EXIT_OK
        }
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
        val rev = p.compile()
        val canonical = ProductToolkit.compile(rev).canonicalJson
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
        val subject =
            args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: throw CliUsageException("explain needs a ruleId")
        val rule = option(args, "--rule")
        val instruction = option(args, "--instruction")
        if (rule == null && instruction == null) return explainAcrossProducts(subject, option(args, "--product"), out, err)
        val rev = loadRevision(subject)
        val sources = ReferenceProducts.byName(subject)?.sources.orEmpty()
        val sourceOf = { id: String -> sources[id]?.toString() }
        val report = if (rule != null) {
            ProductToolkit.explainRule(rev, rule, sourceOf)
        } else {
            ProductToolkit.explainInstruction(rev, instruction!!, sourceOf)
        }
        return if (report["ok"] == true) {
            emit(out, report)
            EXIT_OK
        } else {
            emit(err, report)
            EXIT_FAILURE
        }
    }

    private fun explainAcrossProducts(ruleId: String, productName: String?, out: Appendable, err: Appendable): Int {
        val candidates = if (productName != null) {
            listOfNotNull(ReferenceProducts.byName(productName))
        } else {
            ReferenceProducts.all
        }
        val hits = candidates.mapNotNull { p ->
            p.sources[ruleId]?.let { loc ->
                mapOf(
                    "product" to p.name,
                    "source" to loc.toString(),
                    "irPath" to ProductToolkit.ruleIdPaths(p.compile())[ruleId],
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

    // --- simulate -----------------------------------------------------------------------------

    private fun simulate(args: List<String>, out: Appendable): Int {
        val target = args.firstOrNull()?.takeUnless { it.startsWith("--") }
            ?: usageError("simulate needs an IR file or a product name")
        val portfolio = option(args, "--portfolio") ?: usageError("simulate needs --portfolio <fixture.json>")
        val from = option(args, "--from")?.let { instant(it, "--from") }
        val to = option(args, "--to")?.let { instant(it, "--to") }
        if (from != null && to != null && !from.isBefore(to)) usageError("--from must be before --to")
        val report = ProductToolkit.simulate(
            loadRevision(target),
            Files.readString(Path.of(portfolio)),
            FinanceProductArithmetic,
            from,
            to,
        )
        emit(out, report)
        return EXIT_OK
    }

    private fun instant(v: String, name: String): Instant =
        runCatching { Instant.parse(v) }.getOrElse { usageError("$name must be an ISO-8601 instant, got '$v'") }

    // --- sign ---------------------------------------------------------------------------------

    /**
     * Signs the revision identity envelope with the SAME [HmacIrSigner] the catalog publish path
     * selects in development — but only ever under an offline test key derived from a public
     * constant. `--key-ref` must be `test:<name>`; anything else is refused before any key is
     * resolved, so this command cannot read, and never asks for, a real key.
     */
    private fun sign(args: List<String>, out: Appendable, err: Appendable): Int {
        val target = args.firstOrNull()?.takeUnless { it.startsWith("--") }
            ?: usageError("sign needs an IR file or a product name")
        val keyRef = option(args, "--key-ref") ?: usageError("sign needs --key-ref test:<name>")
        val keyName = keyRef.removePrefix(TEST_KEY_PREFIX)
        if (!keyRef.startsWith(TEST_KEY_PREFIX) || !KEY_NAME.matches(keyName)) {
            return refuse(
                "sign",
                "only an offline test key (--key-ref test:<name>) is accepted; a real key is held by the " +
                    "catalog publish path alone (ADR-0352 §4) and obp never reads one",
                err,
            )
        }
        val revisionId = option(args, "--revision-id")?.let {
            runCatching { UUID.fromString(it) }.getOrElse { usageError("--revision-id must be a UUID") }
        } ?: usageError("sign needs --revision-id <uuid>")
        val revisionNumber = option(args, "--revision-number")?.toLongOrNull()?.takeIf { it > 0 }
            ?: usageError("sign needs --revision-number <positive integer>")
        val rev = loadRevision(target)
        val identity = RevisionIdentity(rev.productCode, revisionId, revisionNumber, rev.irVersion, rev.revisionHash)
        val signature = HmacIrSigner(offlineTestSecret(keyName), keyRef).sign(identity)
        emit(
            out,
            mapOf(
                "ok" to true,
                "offlineTestKey" to true,
                "identity" to mapOf(
                    "productCode" to identity.productCode,
                    "revisionId" to identity.revisionId.toString(),
                    "revisionNumber" to identity.revisionNumber,
                    "irVersion" to identity.irVersion,
                    "revisionHash" to identity.revisionHash,
                ),
                "signature" to mapOf(
                    "keyId" to signature.keyId,
                    "algorithm" to signature.algorithm,
                    "signature" to signature.signature,
                ),
            ),
        )
        return EXIT_OK
    }

    /** A deterministic, PUBLIC test secret: anyone can recompute it, which is exactly why it proves nothing. */
    internal fun offlineTestSecret(keyName: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("$TEST_KEY_DOMAIN:$keyName".toByteArray(Charsets.UTF_8))

    // --- propose ------------------------------------------------------------------------------

    private fun propose(args: List<String>, out: Appendable, err: Appendable): Int {
        val target = args.firstOrNull()?.takeUnless { it.startsWith("--") }
            ?: usageError("propose needs an IR file or a product name")
        val offering = option(args, "--offering") ?: usageError("propose needs --offering <uuid>")
        val schemaId = option(args, "--schema-id") ?: usageError("propose needs --schema-id <id>")
        val schemaVersion = option(args, "--schema-version")?.toIntOrNull()?.takeIf { it > 0 }
            ?: usageError("propose needs --schema-version <positive integer>")
        val plan = try {
            ProductToolkit.proposalPlan(
                loadRevision(target),
                offering,
                mapOf("id" to schemaId, "version" to schemaVersion),
            )
        } catch (e: IllegalArgumentException) {
            usageError(e.message ?: "invalid propose arguments")
        }
        if ("--execute" !in args) {
            emit(out, plan + ("executed" to false))
            return EXIT_OK
        }
        val base = option(args, "--catalog-url") ?: usageError("propose --execute needs --catalog-url URL")
        return executeProposal(base, plan, out, err)
    }

    /** The only network call obp makes: POST one DRAFT revision. The bearer token comes from OBP_TOKEN, never argv. */
    private fun executeProposal(base: String, plan: Map<String, Any?>, out: Appendable, err: Appendable): Int {
        val uri = runCatching { URI.create(base.trimEnd('/') + plan["path"]) }
            .getOrElse { usageError("--catalog-url is not a valid URL") }
        if (uri.scheme != "https" && uri.host !in LOOPBACK) usageError("--catalog-url must be https (or loopback)")
        val request = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .apply { System.getenv("OBP_TOKEN")?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.write(plan["body"])))
            .build()
        val response = try {
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            return refuse("propose", "catalog unreachable: ${e.message}", err)
        }
        val created = response.statusCode() == HTTP_CREATED
        val report = mapOf("ok" to created, "executed" to true, "status" to response.statusCode(), "publishes" to false)
        if (!created) return refuse("propose", "catalog answered ${response.statusCode()}", err)
        emit(out, report)
        return EXIT_OK
    }

    // --- helpers ------------------------------------------------------------------------------

    private const val TEST_KEY_PREFIX = "test:"
    private const val TEST_KEY_DOMAIN = "openbank.obp.offline-test-key.v1"
    private const val HTTP_CREATED = 201
    private val KEY_NAME = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
    private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]")

    private fun usageError(message: String): Nothing = throw CliUsageException(message)

    private fun option(args: List<String>, name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun emit(to: Appendable, tree: Map<String, Any?>) {
        to.appendLine(ProductToolkit.write(tree))
    }

    /** Re-reads canonical JSON text as a tree, for embedding a revision in a scenario document. */
    internal fun revisionTree(rev: ProductRevisionIr): Any? = IrJson.parse(ProductIrCanonical.canonicalJson(rev))
}

class CliUsageException(message: String) : RuntimeException(message)
