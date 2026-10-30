// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.IrDayCount
import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.ir.ProductArithmetic
import com.openbank.libs.productrevision.HmacIrSigner
import com.openbank.libs.productrevision.IrSignature
import com.openbank.libs.productrevision.RevisionIdentity
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.cli.Obp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
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
        assertThat(report["scenarios"].toString()).isEqualTo(ReferenceProducts.all.size.toString())
    }

    @Test
    fun `diff --behavioural renders the shared ProductBehaviourDiff over a portfolio, deterministically`(
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
        val args = arrayOf("diff", a.toString(), b.toString(), "--behavioural", "--portfolio", portfolio)

        val (code, out, err) = run(*args)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        val report = json(out)
        // The CLI prints ProductBehaviourDiff's own document (schema v1), not a CLI-local rendering.
        assertThat(report["schema"]).isEqualTo("openbank.product-behaviour-diff.v1")
        assertThat(report["valid"]).isEqualTo(true)
        assertThat(report["identical"]).isEqualTo(false)
        assertThat(
            out,
        ).contains("\"accounts\":3", "\"ruleId\":\"scheduled.fee\"", "\"ruleId\":\"scheduled.capitalise\"")
        assertThat(err).contains("differ")
        // Same input, same bytes: the report is the approval artefact, so it must be reproducible.
        assertThat(run(*args).second).isEqualTo(out)

        val (same, sameOut, sameErr) = run(
            "diff",
            a.toString(),
            a.toString(),
            "--behavioural",
            "--portfolio",
            portfolio,
        )
        assertThat(same).isEqualTo(Obp.EXIT_OK)
        assertThat(json(sameOut)["identical"]).isEqualTo(true)
        assertThat(sameErr).isEmpty()

        // An explicit range and threshold are honoured; nonsense ones are usage errors.
        val ranged = json(run(*args, "--from", "2026-01-01", "--to", "2026-03-31", "--threshold", "0.50").second)
        assertThat(ranged["range"].toString()).contains("from=2026-01-01", "to=2026-03-31")
        assertThat(ranged["materialityThreshold"]).isEqualTo("0.500000000000")
        assertThat(run(*args, "--from", "2026-02-01", "--to", "2026-01-01").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run(*args, "--threshold", "-1").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run(*args, "--from", "monday").first).isEqualTo(Obp.EXIT_USAGE)
        // A RESOLVED range that is empty or too long is a usage error, never an internal one: --to alone
        // before the earliest openedOn, --from alone after the last fixture day, and a span over 36525 days.
        assertThat(run(*args, "--to", "2025-12-31").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run(*args, "--from", "2026-06-01").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run(*args, "--from", "1900-01-01", "--to", "2200-01-01").first).isEqualTo(Obp.EXIT_USAGE)

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
                "simulate",
                "savings-fixed",
                "--portfolio",
                portfolio,
                "--from",
                "2026-02-01T00:00:00Z",
                "--to",
                "2026-01-01T00:00:00Z",
            ).first,
        ).isEqualTo(Obp.EXIT_USAGE)

        val (code, out, _) = run("simulate", "savings-fixed", "--portfolio", portfolio)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        assertThat(out).contains("\"ruleId\":\"scheduled.fee\"", ReferenceProducts.savingsFixed.compile().revisionHash)
        val (_, windowed, _) = run(
            "simulate",
            "savings-fixed",
            "--portfolio",
            portfolio,
            "--to",
            "2026-01-06T00:00:00Z",
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
            val (code, out, err) = run(
                "sign",
                "savings-fixed",
                "--key-ref",
                ref,
                "--revision-id",
                id,
                "--revision-number",
                "1",
            )
            assertThat(code).describedAs(ref).isEqualTo(Obp.EXIT_REFUSED)
            assertThat(out).isEmpty()
            assertThat(json(err)["refused"].toString()).contains("offline test key")
        }
        assertThat(run("sign", "savings-fixed", "--key-ref", "test:dev").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(
            run("sign", "savings-fixed", "--key-ref", "test:dev", "--revision-id", "x", "--revision-number", "1").first,
        ).isEqualTo(Obp.EXIT_USAGE)

        val args =
            arrayOf("sign", "savings-fixed", "--key-ref", "test:dev", "--revision-id", id, "--revision-number", "3")
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
            run(
                "propose",
                "savings-fixed",
                "--offering",
                "nope",
                "--schema-id",
                "deposit",
                "--schema-version",
                "1",
            ).first,
        ).isEqualTo(Obp.EXIT_USAGE)
        val base = arrayOf(
            "propose",
            "savings-fixed",
            "--offering",
            "00000000-0000-0000-0000-0000000000a1",
            "--schema-id",
            "deposit",
            "--schema-version",
            "1",
        )
        assertThat(run(*base, "--execute").first).isEqualTo(Obp.EXIT_USAGE)
        assertThat(run(*base, "--execute", "--catalog-url", "http://catalog.example").first).isEqualTo(Obp.EXIT_USAGE)
        // An unreachable catalog is "unavailable" (69), distinct from a refused signing key (2).
        val (down, _, downErr) = run(*base, "--execute", "--catalog-url", "http://localhost:9")
        assertThat(down).isEqualTo(Obp.EXIT_UNAVAILABLE)
        assertThat(json(downErr)["executed"]).isEqualTo(false)

        val (code, out, _) = run(*base)
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        val plan = json(out)
        assertThat(plan["executed"]).isEqualTo(false)
        assertThat(plan["publishes"]).isEqualTo(false)
        assertThat(plan["path"].toString()).endsWith("/revisions").doesNotContain("publish")
    }

    @Test
    fun `propose with an invalid IR is exit 1 with the structured problems, not a usage error`(@TempDir dir: Path) {
        val broken = dir.resolve("broken.json")
        Files.writeString(broken, ReferenceProducts.termDeposit.canonicalJson().replace("\"ACT_360\"", "\"ACT_999\""))
        val (code, out, err) = run(
            "propose",
            broken.toString(),
            "--offering",
            "00000000-0000-0000-0000-0000000000a1",
            "--schema-id",
            "deposit",
            "--schema-version",
            "1",
        )
        assertThat(code).isEqualTo(Obp.EXIT_FAILURE)
        assertThat(out).isEmpty()
        assertThat(err).contains("\"problems\"", "/interest/dayCount")
    }

    @Test
    fun `a missing or malformed input file or JSON argument is exit 65 with one canonical line, never a trace`(
        @TempDir dir: Path,
    ) {
        fun assertInputError(vararg args: String) {
            val (code, out, err) = run(*args)
            assertThat(code).describedAs(args.joinToString(" ")).isEqualTo(Obp.EXIT_INPUT)
            assertThat(out).isEmpty()
            assertThat(err.lines().filter { it.isNotBlank() }).hasSize(1)
            assertThat(json(err)["inputError"].toString()).isNotBlank()
        }
        val notJson = dir.resolve("p.json").also { Files.writeString(it, "not json") }
        val noAt = dir.resolve("q.json").also {
            Files.writeString(
                it,
                """{"accounts":[{"id":"a","state":{"currency":"EUR","openedOn":"2026-01-01"},"steps":[{"hook":"scheduled"}]}]}""",
            )
        }
        val badAt = dir.resolve("r.json").also {
            Files.writeString(
                it,
                """{"accounts":[{"id":"a","state":{"currency":"EUR","openedOn":"2026-01-01"},"steps":[{"at":"x","hook":"scheduled"}]}]}""",
            )
        }
        assertInputError("simulate", "savings-fixed", "--portfolio", dir.resolve("absent.json").toString())
        assertInputError("simulate", "savings-fixed", "--portfolio", notJson.toString())
        assertInputError("simulate", "savings-fixed", "--portfolio", noAt.toString())
        assertInputError("simulate", "savings-fixed", "--portfolio", badAt.toString())
        assertInputError("explain", "savings-fixed", "--instruction", "[1,2]")
    }

    @Test
    fun `an exception thrown during evaluation is an internal error (70) with a stack trace, not bad input`() {
        val failing = object : ProductArithmetic by FinanceProductArithmetic {
            override fun dailyAccrual(
                balance: BigDecimal,
                annualRate: BigDecimal,
                dayCount: IrDayCount,
                date: LocalDate,
                accrualRounding: RoundingPolicy,
            ): BigDecimal = throw IllegalArgumentException("synthetic interpreter defect")
        }
        val portfolio = Path.of("portfolios", "savings-sample.json").toString()
        Obp.arithmetic = failing
        try {
            val (code, out, err) = run("simulate", "savings-fixed", "--portfolio", portfolio)
            assertThat(code).isEqualTo(Obp.EXIT_INTERNAL)
            assertThat(out).isEmpty()
            assertThat(err).contains("internal error", "synthetic interpreter defect", "\tat ")
            assertThat(err).doesNotContain("inputError")
        } finally {
            Obp.arithmetic = FinanceProductArithmetic
        }
        assertThat(run("simulate", "savings-fixed", "--portfolio", portfolio).first).isEqualTo(Obp.EXIT_OK)
    }

    @Test
    fun `compile of a file prints raw canonical IR by default and the MCP-shaped report under --json`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("draft.json")
        Files.writeString(file, ReferenceProducts.savingsFixed.canonicalJson())
        val (code, out, err) = run("compile", file.toString())
        assertThat(code).isEqualTo(Obp.EXIT_OK)
        // `obp compile x.json > ir.json` must write an IR document, which lint accepts.
        assertThat(out.trimEnd()).isEqualTo(ReferenceProducts.savingsFixed.canonicalJson())
        assertThat(json(err)["revisionHash"]).isEqualTo(ReferenceProducts.savingsFixed.compile().revisionHash)
        val roundTrip = dir.resolve("ir.json").also { Files.writeString(it, out) }
        assertThat(run("lint", roundTrip.toString()).first).isEqualTo(Obp.EXIT_OK)

        val (wrapped, wrappedOut, _) = run("compile", file.toString(), "--json")
        assertThat(wrapped).isEqualTo(Obp.EXIT_OK)
        assertThat(json(wrappedOut).keys).containsExactlyInAnyOrder("ok", "revisionHash", "ir")
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
                "propose",
                "savings-fixed",
                "--offering",
                "00000000-0000-0000-0000-0000000000a1",
                "--schema-id",
                "deposit",
                "--schema-version",
                "1",
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
