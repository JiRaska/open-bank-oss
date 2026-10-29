// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.product.ir.IrJson
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignature
import com.openbank.libs.productrevision.RevisionIdentity
import com.openbank.products.cli.Obp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

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
        assertThat(report["scenarios"].toString()).isEqualTo("3")
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
    fun `simulate needs a portfolio and a sane window, then prints canonical instructions deterministically`() {
        val portfolio = Path.of("portfolios", "savings-sample.json").toString()
        assertThat(run("simulate", "savings-fixed").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run("simulate", "--portfolio", portfolio).first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run("simulate", "savings-fixed", "--portfolio", portfolio, "--from", "monday").first)
            .isEqualTo(Obp.EXIT_USAGE)
        assertThat(
            run(
                "simulate", "savings-fixed", "--portfolio", portfolio,
                "--from", "2026-02-01T00:00:00Z", "--to", "2026-01-01T00:00:00Z",
            ).first,
        ).isEqualTo(Obp.EXIT_USAGE)

        val (code, out, _) = run("simulate", "savings-fixed", "--portfolio", portfolio)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(out).contains("\"ruleId\":\"scheduled.fee\"", ReferenceProducts.savingsFixed.compile().revisionHash)
        val (_, windowed, _) = run(
            "simulate", "savings-fixed", "--portfolio", portfolio, "--to", "2026-01-06T00:00:00Z",
        )
        assertThat(windowed).doesNotContain("scheduled.fee")
    }

    @Test
    fun `explain --rule and --instruction refuse what the revision does not declare, map what it does`() {
        val (miss, _, missErr) = run("explain", "savings-fixed", "--rule", "no.such")
        assertThat(miss).isEqualTo(Obp.EXIT_FAILURE)
        assertThat(json(missErr)["ok"]).isEqualTo(false)
        val foreign = """{"ruleId":"scheduled.fee","revisionHash":"${"0".repeat(64)}"}"""
        assertThat(run("explain", "savings-fixed", "--instruction", foreign).first).isEqualTo(Obp.EXIT_FAILURE)

        val (code, out, _) = run("explain", "savings-fixed", "--rule", "scheduled.fee")
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(json(out)["irPath"]).isEqualTo("/hooks/scheduled/2")
        assertThat(json(out)["source"].toString()).startsWith("ReferenceProducts.kt:")
        val hash = ReferenceProducts.savingsFixed.compile().revisionHash
        val own = """{"ruleId":"fee.maintenance","revisionHash":"$hash","kind":"POST"}"""
        val (ok, ownOut, _) = run("explain", "savings-fixed", "--instruction", own)
        assertThat(ok).isEqualTo(Obp.EXIT_OK)
        assertThat(json(ownOut)["irPath"]).isEqualTo("/fees/0")
    }

    @Test
    fun `sign refuses anything but an offline test key, then produces a verifiable identity-bound signature`() {
        val id = "00000000-0000-0000-0000-0000000000a1"
        for (ref in listOf("vault:transit/products", "test:../etc/passwd", "test:", "TEST:x")) {
            val (code, out, err) = run("sign", "savings-fixed", "--key-ref", ref, "--revision-id", id, "--revision-number", "1")
            assertThat(code).describedAs(ref).isEqualTo(Obp.EXIT_REFUSED)
            assertThat(out).isEmpty()
            assertThat(json(err)["refused"].toString()).contains("offline test key")
        }
        assertThat(run("sign", "savings-fixed", "--key-ref", "test:dev").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(
            run("sign", "savings-fixed", "--key-ref", "test:dev", "--revision-id", "x", "--revision-number", "1").first,
        ).isEqualTo(Obp.EXIT_USAGE)

        val args = arrayOf("sign", "savings-fixed", "--key-ref", "test:dev", "--revision-id", id, "--revision-number", "3")
        val (code, out, _) = run(*args)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(out).isEqualTo(run(*args).second)
        val report = json(out)
        assertThat(report["offlineTestKey"]).isEqualTo(true)
        @Suppress("UNCHECKED_CAST")
        val sig = report["signature"] as Map<String, Any?>
        val rev = ReferenceProducts.savingsFixed.compile()
        val identity = RevisionIdentity(rev.productCode, UUID.fromString(id), 3, rev.irVersion, rev.revisionHash)
        val verifier = HmacIrSigner(Obp.offlineTestSecret("dev"), "test:dev")
        val signature = IrSignature(sig["keyId"] as String, sig["algorithm"] as String, sig["signature"] as String)
        assertThat(verifier.verify(identity, signature)).isTrue()
        // Bound to the identity, not the bare hash: another revision number does not verify.
        assertThat(verifier.verify(identity.copy(revisionNumber = 4), signature)).isFalse()
    }

    @Test
    fun `propose validates its arguments, plans a DRAFT without --execute and never publishes`() {
        assertThat(run("propose", "savings-fixed").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(
            run("propose", "savings-fixed", "--offering", "nope", "--schema-id", "deposit", "--schema-version", "1").first,
        ).isEqualTo(Obp.EXIT_USAGE)
        val base = arrayOf(
            "propose", "savings-fixed", "--offering", "00000000-0000-0000-0000-0000000000a1",
            "--schema-id", "deposit", "--schema-version", "1",
        )
        assertThat(run(*base, "--execute").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run(*base, "--execute", "--catalog-url", "http://catalog.example").first).isEqualTo(Obp.EXIT_USAGE)
        // An unreachable loopback catalog is a refusal, not a crash.
        assertThat(run(*base, "--execute", "--catalog-url", "http://127.0.0.1:9").first).isEqualTo(Obp.EXIT_REFUSED)

        val (code, out, _) = run(*base)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        val plan = json(out)
        assertThat(plan["executed"]).isEqualTo(false)
        assertThat(plan["publishes"]).isEqualTo(false)
        assertThat(plan["path"].toString()).endsWith("/revisions").doesNotContain("publish")
    }

    @Test
    fun `every offline subcommand prints identical bytes when run twice`() {
        val portfolio = Path.of("portfolios", "savings-sample.json").toString()
        val commands = listOf(
            arrayOf("lint", "savings-fixed"),
            arrayOf("compile", "term-deposit"),
            arrayOf("test"),
            arrayOf("simulate", "savings-fixed", "--portfolio", portfolio),
            arrayOf("explain", "savings-fixed", "--rule", "interest.base"),
            arrayOf(
                "propose", "savings-fixed", "--offering", "00000000-0000-0000-0000-0000000000a1",
                "--schema-id", "deposit", "--schema-version", "1",
            ),
        )
        for (c in commands) assertThat(run(*c)).describedAs(c.joinToString(" ")).isEqualTo(run(*c))
    }

    @Test
    fun `unknown and empty commands print usage`() {
        assertThat(run().first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run("frobnicate").first).isEqualTo(Obp.EXIT_USAGE)
    }
}
