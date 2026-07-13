// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.openbank.flakytest.application.port.out.TestScanPort
import com.openbank.flakytest.domain.model.PactGatedTestClass
import com.openbank.flakytest.domain.model.PactProviderDeclaration
import com.openbank.flakytest.domain.model.RunBlockingViolation
import com.openbank.flakytest.domain.model.TestCountSample
import com.openbank.flakytest.domain.model.TestScanSnapshot
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Direct repo-checkout grep/text-scan of every service's `src/test/kotlin` tree and each module's
 * JUnit `build/test-results/test` reports (`read.governance`, ADR-0168 checks 1-4). Deliberately
 * shallow — line-local pattern matching, not a Kotlin PSI/compiler-frontend parse — mirroring
 * `check-test-runblocking-unit.sh`'s own "grep, not a compiler-frontend parse" design and
 * authz-policy-auditor's/docs-truth-agent's/release-steward's real (not stubbed) grep/text-scan
 * adapter precedent, since this agent runs from within the monorepo.
 */
@ApplicationScoped
class TestScanAdapter(private val config: FlakyTestHunterConfig) : TestScanPort {

    private val log = Logger.getLogger(TestScanAdapter::class.java)

    override suspend fun scan(): TestScanSnapshot {
        val root = Path.of(config.repoRoot())
        log.infof("Scanning fleet-wide Kotlin test sources under %s", root.toAbsolutePath())

        val modules = ModuleDirs.discover(root)
        val testFiles = modules.flatMap { TestSourceFiles.discover(it) }

        val runBlockingViolations = testFiles.flatMap { TestTextScanner.runBlockingViolations(root, it) }
        val pactGatedClasses = testFiles.flatMap { TestTextScanner.pactGatedClasses(root, it) }
        val pactProviderDeclarations = testFiles.flatMap { TestTextScanner.pactProviderDeclarations(root, it) }
        val testCountSamples = TestCountScanner.sample(root, modules, testFiles)

        return TestScanSnapshot(
            testFilesScanned = testFiles.size,
            runBlockingViolations = runBlockingViolations,
            pactGatedClasses = pactGatedClasses,
            pactProviderDeclarations = pactProviderDeclarations,
            testCountSamples = testCountSamples,
        )
    }
}

/** Every top-level `openbank-*` module directory — the unit checks 2-4 group findings by. */
private object ModuleDirs {
    fun discover(root: Path): List<Path> {
        if (!root.exists()) return emptyList()
        return Files.list(root).use { stream ->
            stream.asSequence()
                .filter { it.isDirectory() && it.name.startsWith("openbank-") }
                .sortedBy { it.name }
                .toList()
        }
    }
}

/** Every `.kt` file under a module's `src/test/kotlin` tree. Scoped to that one directory (not a
 * repo-wide walk pruning `build`/`.git`/`node_modules`) so this stays cheap on a fleet the size of
 * open-bank-oss's — the same "walk the specific tree this check needs, not the whole repo" shape
 * `RegoFiles` uses in authz-policy-auditor's `PolicyScanAdapter`. */
private object TestSourceFiles {
    fun discover(module: Path): List<Path> {
        val testKotlinDir = module.resolve("src/test/kotlin")
        if (!testKotlinDir.exists()) return emptyList()
        return Files.walk(testKotlinDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension == "kt" }
                .toList()
        }
    }
}

/** Line-level scans over a single test `.kt` file's text — checks 1, 2 and 3 (ADR-0168). Split out
 * of [TestScanAdapter] to keep that class's function count within the fleet's `TooManyFunctions`
 * detekt threshold, the same reason authz-policy-auditor's `PolicyScanAdapter` splits out
 * `PolicyTextScanner`. */
private object TestTextScanner {
    // Mirrors check-test-runblocking-unit.sh's own pattern (`fun [A-Za-z\`].*\) = runBlocking ?\{`,
    // then a `: Unit` exclusion) but generalized to the other coroutine builders sharing the exact
    // same "expression-body function whose last statement is non-Unit" JUnit5 silent-drop shape —
    // the "similar mistake with a different coroutine builder" the guard's one literal doesn't cover.
    private val EXPRESSION_BODY_COROUTINE =
        Regex("""fun [A-Za-z`].*\) = (runBlocking|runTest|GlobalScope\.launch|GlobalScope\.async) ?\{""")
    private const val UNIT_RETURN_MARKER = ": Unit"

    private const val PACT_GATED_MARKER = "pactbroker.url"
    private val CLASS_DECLARATION = Regex("""^\s*(?:open\s+)?class\s+([A-Za-z0-9_]+)""")

    private val PACT_PROVIDER_ANNOTATION = Regex("""@Provider\("([^"]+)"\)""")

    // Check 1: every expression-body test/helper function using the unsafe coroutine-builder shape
    // without an explicit ': Unit' return type.
    fun runBlockingViolations(root: Path, file: Path): List<RunBlockingViolation> {
        val rel = root.relativize(file).toString()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        return lines.withIndex().mapNotNull { (idx, rawLine) ->
            val match = EXPRESSION_BODY_COROUTINE.find(rawLine) ?: return@mapNotNull null
            if (rawLine.contains(UNIT_RETURN_MARKER)) return@mapNotNull null
            RunBlockingViolation(file = rel, line = idx + 1, builder = match.groupValues[1], snippet = rawLine.trim())
        }
    }

    // Check 2: every class gated on @EnabledIfSystemProperty(named = "pactbroker.url", ...) — always
    // skipped locally. The class name is a best-effort forward scan from the annotation line (Pact
    // provider verification tests apply this at class level in practice); falls back to the file's
    // own name when no class declaration is found after the annotation.
    fun pactGatedClasses(root: Path, file: Path): List<PactGatedTestClass> {
        val rel = root.relativize(file).toString()
        val module = rel.substringBefore('/')
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        return lines.withIndex().mapNotNull { (idx, rawLine) ->
            if (!rawLine.contains(PACT_GATED_MARKER)) return@mapNotNull null
            val className = lines.drop(idx)
                .firstNotNullOfOrNull { CLASS_DECLARATION.find(it)?.groupValues?.get(1) }
                ?: file.name.removeSuffix(".kt")
            PactGatedTestClass(file = rel, module = module, className = className)
        }
    }

    // Check 3: every @Provider("X") class-level annotation, tagged with the provider name it
    // declares — collision detection (two distinct files sharing the same provider name) happens in
    // DetectDriftActivityImpl, not here.
    fun pactProviderDeclarations(root: Path, file: Path): List<PactProviderDeclaration> {
        val rel = root.relativize(file).toString()
        val text = runCatching { file.readText() }.getOrElse { return emptyList() }
        return PACT_PROVIDER_ANNOTATION.findAll(text)
            .map { PactProviderDeclaration(file = rel, providerName = it.groupValues[1]) }
            .toList()
    }
}

/** Check 4 (ADR-0168): declared-vs-executed `@Test` count per module. Only modules with at least one
 * JUnit XML report under `build/test-results/test` are sampled — a module never built in this
 * checkout is silently excluded, not misreported as a 100% drop. */
private object TestCountScanner {
    private val TEST_ANNOTATION = Regex("""(?m)^\s*@Test\b""")
    private val TESTSUITE_TESTS_ATTR = Regex("""<testsuite\b[^>]*\stests="(\d+)"""")

    fun sample(root: Path, modules: List<Path>, testFiles: List<Path>): List<TestCountSample> {
        val filesByModule = testFiles.groupBy { root.relativize(it).toString().substringBefore('/') }
        return modules.mapNotNull { module ->
            val executed = executedCount(module) ?: return@mapNotNull null
            val declared = filesByModule[module.name].orEmpty().sumOf { declaredCount(it) }
            TestCountSample(module = module.name, declaredCount = declared, executedCount = executed)
        }
    }

    private fun declaredCount(file: Path): Int {
        val text = runCatching { file.readText() }.getOrElse { return 0 }
        return TEST_ANNOTATION.findAll(text).count()
    }

    // Returns null (not 0) when the module has no test-results reports yet — the module was never
    // built in this checkout, which is a different, unremarkable fact from "every test was dropped."
    private fun executedCount(module: Path): Int? {
        val resultsDir = module.resolve("build/test-results/test")
        if (!resultsDir.exists() || !resultsDir.isDirectory()) return null
        val reportFiles = Files.list(resultsDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension == "xml" }
                .toList()
        }
        if (reportFiles.isEmpty()) return null
        return reportFiles.sumOf { report ->
            val text = runCatching { report.readText() }.getOrElse { "" }
            TESTSUITE_TESTS_ATTR.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}
