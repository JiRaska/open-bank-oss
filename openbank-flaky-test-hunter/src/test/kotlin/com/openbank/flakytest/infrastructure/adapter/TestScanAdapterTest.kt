// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Exercises [TestScanAdapter] end to end against a throwaway "repo checkout" tree (a `@TempDir`, not
 * this module's own `src/test/kotlin` — see the individual fixture comments for why some snippets are
 * built via string interpolation rather than written as literal source: the unsafe expression-body
 * `runBlocking` shape, spelled out literally as source text anywhere under `src/test/**/*.kt`, would
 * itself trip `.github/scripts/check-test-runblocking-unit.sh` — that guard greps text and cannot tell
 * a fixture string from real code).
 */
class TestScanAdapterTest {

    private fun configFor(root: Path): FlakyTestHunterConfig = object : FlakyTestHunterConfig {
        // An anonymous implementation, not a mock, so every accessor added to the mapping has to
        // be answered here — which is why adding the sweep's cron broke this file and not the
        // four sibling adapter tests, which all use mockk.
        override fun checkCron() = "0 30 6 ? * SUN"

        override fun githubApiUrl() = "https://api.github.com"

        override fun githubRepo() = "JiRaska/open-bank-oss"

        override fun llmGatewayUrl() = "http://litellm.ai-platform:4000"

        override fun repoRoot() = root.toString()
    }

    private fun testSourceDir(root: Path, module: String = "openbank-sample-service"): Path {
        val dir = root.resolve("$module/src/test/kotlin/com/openbank/sample")
        dir.createDirectories()
        return dir
    }

    @Test
    fun `flags a bare runBlocking expression-body function as a violation`(@TempDir tempRoot: Path): Unit =
        runBlocking {
            val dir = testSourceDir(tempRoot)
            // Built via interpolation, not a literal 'fun ... = runBlocking {' substring in this file.
            val builder = "runBlocking"
            dir.resolve("SampleTest.kt").writeText(
                """
            package com.openbank.sample

            class SampleTest {
                fun brokenTest() = $builder { 1 }
            }
                """.trimIndent(),
            )

            val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

            assertThat(snapshot.runBlockingViolations).hasSize(1)
            assertThat(snapshot.runBlockingViolations.single().builder).isEqualTo("runBlocking")
        }

    @Test
    fun `does not flag an expression-body function with an explicit colon Unit return type`(
        @TempDir tempRoot: Path,
    ): Unit = runBlocking {
        val dir = testSourceDir(tempRoot)
        val builder = "runBlocking"
        dir.resolve("SafeTest.kt").writeText(
            """
            package com.openbank.sample

            class SafeTest {
                fun safeTest(): Unit = $builder { doSomething() }
            }
            """.trimIndent(),
        )

        val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

        assertThat(snapshot.runBlockingViolations).isEmpty()
    }

    @Test
    fun `does not flag runTest -- its lambda type is fixed at Unit so it can never silently drop a test`(
        @TempDir tempRoot: Path,
    ): Unit = runBlocking {
        val dir = testSourceDir(tempRoot)
        // Regression test for the review finding: runTest was previously included in the detector's
        // pattern, flagging this repo's own CLAUDE.md-recommended fix for the runBlocking footgun as a
        // violation. kotlinx-coroutines-test's runTest has a lambda parameter type fixed at Unit (JVM
        // 'actual typealias TestResult = Unit'), unlike runBlocking's generic <T>, so it is never subject
        // to the non-Unit-inference bug this check exists to catch -- written as a literal here (not via
        // interpolation) since check-test-runblocking-unit.sh's own regex only ever matches the literal
        // 'runBlocking' builder, so this line was never at risk of tripping that guard either.
        dir.resolve("RunTestUsage.kt").writeText(
            """
            package com.openbank.sample

            class RunTestUsage {
                fun viaRunTest() = runTest { doSomething() }
            }
            """.trimIndent(),
        )

        val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

        assertThat(snapshot.runBlockingViolations).isEmpty()
    }

    @Test
    fun `flags GlobalScope launch and async expression-body functions as violations`(@TempDir tempRoot: Path): Unit =
        runBlocking {
            val dir = testSourceDir(tempRoot)
            val launch = "GlobalScope.launch"
            val async = "GlobalScope.async"
            dir.resolve("GlobalScopeUsage.kt").writeText(
                """
            package com.openbank.sample

            class GlobalScopeUsage {
                fun brokenLaunch() = $launch { 1 }
                fun brokenAsync() = $async { 2 }
            }
                """.trimIndent(),
            )

            val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

            assertThat(snapshot.runBlockingViolations).extracting("builder")
                .containsExactlyInAnyOrder("GlobalScope.launch", "GlobalScope.async")
        }

    @Test
    fun `flags a Pact provider class gated on pactbroker url and records its provider declaration`(
        @TempDir tempRoot: Path,
    ): Unit = runBlocking {
        val dir = testSourceDir(tempRoot)
        dir.resolve("SamplePactProviderTest.kt").writeText(
            """
            package com.openbank.sample

            @EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
            @Provider("openbank-sample-service")
            class SamplePactProviderTest {
                fun verify() {}
            }
            """.trimIndent(),
        )

        val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

        assertThat(snapshot.pactGatedClasses).hasSize(1)
        assertThat(snapshot.pactGatedClasses.single().className).isEqualTo("SamplePactProviderTest")
        assertThat(snapshot.pactProviderDeclarations).hasSize(1)
        assertThat(snapshot.pactProviderDeclarations.single().providerName).isEqualTo("openbank-sample-service")
    }

    @Test
    fun `samples declared source Test count against executed JUnit XML count per module`(
        @TempDir tempRoot: Path,
    ): Unit = runBlocking {
        val dir = testSourceDir(tempRoot)
        dir.resolve("CountedTest.kt").writeText(
            """
            package com.openbank.sample

            class CountedTest {
                @Test
                fun first() {}

                @Test
                fun second() {}

                @Test
                fun third() {}
            }
            """.trimIndent(),
        )
        val resultsDir = tempRoot.resolve("openbank-sample-service/build/test-results/test")
        resultsDir.createDirectories()
        resultsDir.resolve("TEST-CountedTest.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<testsuite name="CountedTest" tests="2" failures="0" errors="0" skipped="0"></testsuite>""",
        )

        val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

        val sample = snapshot.testCountSamples.single { it.module == "openbank-sample-service" }
        assertThat(sample.declaredCount).isEqualTo(3)
        assertThat(sample.executedCount).isEqualTo(2)
    }

    @Test
    fun `excludes a module with no build test-results reports from the test count sample`(
        @TempDir tempRoot: Path,
    ): Unit = runBlocking {
        testSourceDir(tempRoot)

        val snapshot = TestScanAdapter(configFor(tempRoot)).scan()

        assertThat(snapshot.testCountSamples).isEmpty()
    }
}
