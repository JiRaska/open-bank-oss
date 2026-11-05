// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.product.ir.IrJson
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.cli.Obp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** `obp` prints canonical JSON, exits as documented, and refuses what only the publish path may do. */
class ObpCliTest {

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = Obp.run(args.toList(), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(text: String): Map<String, Any?> =
        IrJson.parse(text.lines().last { it.isNotBlank() }) as Map<String, Any?>

    @Test
    fun `lint accepts a reference product and a file, and rejects an invalid document`(@TempDir dir: Path) {
        val (code, out, _) = run("lint", "savings-fixed")
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(json(out)["revisionHash"]).isEqualTo(ReferenceProducts.savingsFixed.compile().revisionHash)

        val file = dir.resolve("td.json")
        Files.writeString(file, ReferenceProducts.termDeposit.canonicalJson())
        assertThat(run("lint", file.toString()).first).isEqualTo(Obp.EXIT_OK)

        val broken = dir.resolve("broken.json")
        Files.writeString(broken, ReferenceProducts.termDeposit.canonicalJson().replace("\"ACT_360\"", "\"ACT_999\""))
        val (bad, _, err) = run("lint", broken.toString())
        assertThat(bad).isEqualTo(Obp.EXIT_FAILURE)
        assertThat(json(err)["ok"]).isEqualTo(false)
        assertThat(err).contains("/interest/dayCount")

        assertThat(run("lint", "no-such-product").first).isEqualTo(Obp.EXIT_USAGE)
    }

    @Test
    fun `compile writes canonical IR, check is green against it and red against drift`(@TempDir dir: Path) {
        val (code, out, _) = run("compile", "all", "--out", dir.toString())
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(json(out)["ok"]).isEqualTo(true)
        assertThat(Files.readString(dir.resolve("savings-fixed.json")).trimEnd())
            .isEqualTo(ReferenceProducts.savingsFixed.canonicalJson())
        assertThat(run("compile", "all", "--out", dir.toString(), "--check").first).isEqualTo(Obp.EXIT_OK)

        Files.writeString(
            dir.resolve("savings-fixed.json"),
            Files.readString(dir.resolve("savings-fixed.json")).replace("0.0250", "0.0350"),
        )
        val (drift, driftOut, driftErr) = run("compile", "all", "--out", dir.toString(), "--check")
        assertThat(drift).isEqualTo(Obp.EXIT_FAILURE)
        assertThat(driftOut).contains("\"status\":\"drift\"")
        assertThat(driftErr).contains("differ from the DSL")

        val (printed, printedOut, _) = run("compile", "term-deposit")
        assertThat(printed).isEqualTo(Obp.EXIT_OK)
        assertThat(printedOut).contains(ReferenceProducts.termDeposit.canonicalJson())
        assertThat(run("compile", "nope").first).isEqualTo(Obp.EXIT_USAGE)
    }

    @Test
    fun `test runs the golden scenarios through the shared runner`() {
        val (code, out, _) = run("test")
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        val report = json(out)
        assertThat(report["ok"]).isEqualTo(true)
        assertThat(report["failed"].toString()).isEqualTo("0")
        assertThat(report["scenarios"].toString()).isEqualTo(ReferenceProducts.all.size.toString())
    }

    @Test
    fun `diff --behavioural reports per-rule posting deltas over a portfolio and nothing for identical revisions`(
        @TempDir dir: Path,
    ) {
        val a = dir.resolve("a.json")
        val b = dir.resolve("b.json")
        Files.writeString(a, ReferenceProducts.savingsFixed.canonicalJson())
        // Revision B: the fee doubles and the rate goes up — both reach the customer deposit.
        Files.writeString(
            b,
            Files.readString(
                a,
            ).replace("\"amount\":\"2.0000\"", "\"amount\":\"4.0000\"").replace("0.0250000000", "0.0350000000"),
        )
        val portfolio = Path.of("portfolios", "savings-sample.json").toString()

        val (code, out, err) = run("diff", a.toString(), b.toString(), "--behavioural", "--portfolio", portfolio)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        val report = json(out)
        assertThat(report["accounts"].toString()).isEqualTo("3")
        assertThat(report["changedRules"].toString()).isNotEqualTo("0")
        // The fee doubles (-2.00 per charge) and the capitalised interest grows; the accrual itself never touches
        // the customer deposit, so interest.base is NOT a changed rule even though its amounts differ.
        assertThat(out).contains("\"ruleId\":\"scheduled.fee\"", "\"customerDepositDelta\":\"-2.000000000000\"")
        assertThat(out).contains("\"ruleId\":\"scheduled.capitalise\"")
        assertThat(out).doesNotContain("\"ruleId\":\"interest.base\"")
        assertThat(err).contains("post differently")

        val (same, sameOut, sameErr) = run(
            "diff",
            a.toString(),
            a.toString(),
            "--behavioural",
            "--portfolio",
            portfolio,
        )
        assertThat(same).isEqualTo(Obp.EXIT_OK)
        assertThat(json(sameOut)["changedRules"].toString()).isEqualTo("0")
        assertThat(sameErr).isEmpty()

        assertThat(run("diff", a.toString(), b.toString()).first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run("diff", a.toString(), "--behavioural", "--portfolio", portfolio).first).isEqualTo(Obp.EXIT_USAGE)
    }

    @Test
    fun `explain maps a ruleId to its DSL source line, scoped by product when asked`() {
        val (code, out, _) = run("explain", "fee.maintenance")
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(
            out,
        ).contains("\"product\":\"current-account\"", "\"product\":\"savings-fixed\"", "ReferenceProducts.kt:")
        val (scoped, scopedOut, _) = run("explain", "fee.maintenance", "--product", "current-account")
        assertThat(scoped).isEqualTo(Obp.EXIT_OK)
        assertThat(scopedOut).doesNotContain("savings-fixed")
        val (missing, _, err) = run("explain", "rule.nobody-declares")
        assertThat(missing).isEqualTo(Obp.EXIT_FAILURE)
        assertThat(err).contains("no reference product declares")
        assertThat(run("explain").first).isEqualTo(Obp.EXIT_USAGE)
    }

    @Test
    fun `sign and propose refuse with a reason, unknown and empty commands print usage`() {
        for (cmd in listOf("sign", "propose")) {
            val (code, out, err) = run(cmd)
            assertThat(code).isEqualTo(Obp.EXIT_REFUSED)
            assertThat(out).isEmpty()
            assertThat(json(err)["refused"].toString()).isNotEmpty()
        }
        assertThat(run().first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run("frobnicate").first).isEqualTo(Obp.EXIT_USAGE)
    }
}
