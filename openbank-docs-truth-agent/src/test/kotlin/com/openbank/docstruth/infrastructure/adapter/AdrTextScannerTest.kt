// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.domain.model.AdrDeliveryStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class AdrTextScannerTest {

    @Test
    fun `parses the Delivery-Status line`() {
        val text = """
            # ADR-0166 — docs-truth-agent

            Delivery-Status: Shipped
        """.trimIndent()
        val record = AdrTextScanner.parse("0166-docs-truth-agent.md", text)
        assertThat(record).isNotNull
        assertThat(record!!.id).isEqualTo("ADR-0166")
        assertThat(record.deliveryStatus).isEqualTo(AdrDeliveryStatus.SHIPPED)
    }

    @Test
    fun `defaults to NOT_TRACKED when no Delivery-Status line is present`() {
        val text = "# ADR-0001 — Some decision\n\nNo status line here.\n"
        val record = AdrTextScanner.parse("0001-some-decision.md", text)
        assertThat(record!!.deliveryStatus).isEqualTo(AdrDeliveryStatus.NOT_TRACKED)
    }

    @Test
    fun `returns null for a filename that does not match the ADR_FILENAME convention`() {
        val record = AdrTextScanner.parse("README.md", "Delivery-Status: Shipped")
        assertThat(record).isNull()
    }

    @Test
    fun `captures a PascalCase backtick token as a claimed artifact`() {
        val text = """
            Delivery-Status: Shipped

            `LedgerService` records the journal entry.
        """.trimIndent()
        val record = AdrTextScanner.parse("0100-example.md", text)
        assertThat(record!!.claimedArtifacts.map { it.name }).contains("LedgerService")
    }

    @Test
    fun `tags an artifact as claimed-not-yet-built when the sentence says so`() {
        val text = """
            Delivery-Status: Planned

            `OnlineFeatureStore` does not exist yet; it is planned for a future phase.
        """.trimIndent()
        val record = AdrTextScanner.parse("0139-feature-store.md", text)
        val claim = record!!.claimedArtifacts.single { it.name == "OnlineFeatureStore" }
        assertThat(claim.claimedNotYetBuilt).isTrue()
    }

    @Test
    fun `captures an enforced gate reference distinct from an advisory one`() {
        val text = """
            Delivery-Status: Shipped

            The `duplicate-yaml-keys` gate is enforced fleet-wide.
            The `docs-currency` gate is advisory only.
        """.trimIndent()
        val record = AdrTextScanner.parse("0144-gates.md", text)
        val enforcements = record!!.claimedEnforcements.associate { it.gateName to it.claimedEnforced }
        assertThat(enforcements["duplicate-yaml-keys"]).isTrue()
        assertThat(enforcements["docs-currency"]).isFalse()
    }

    // Regression test for a second real bug found while writing this coverage: rules.yaml gate
    // names ("api-contract", "db-migration", "duplicate-yaml-keys" — plain lowercase,
    // hyphen-separated identifiers, e.g. docs/adr/0048's own `api-contract` gate reference) used to
    // gate BOTH artifact and enforcement recording behind the same `looksLikeArtifact` check, which
    // a kebab-case gate name can never pass (not PascalCase, no "/", no script suffix) — so
    // ENFORCEMENT_STATUS_MISMATCH (check 3) could never fire for the common case of an ADR quoting
    // a gate by its literal rules.yaml name.
    @Test
    fun `captures a plain kebab-case rules-yaml gate name as an enforcement claim`() {
        val text = """
            Delivery-Status: Shipped

            The `api-contract` gate stays advisory (consistent with ADR-0029's "enforce last" phasing).
        """.trimIndent()
        val record = AdrTextScanner.parse("0048-example.md", text)
        val claim = record!!.claimedEnforcements.singleOrNull { it.gateName == "api-contract" }
        assertThat(claim).isNotNull
        assertThat(claim!!.claimedEnforced).isFalse()
    }

    @Test
    fun `does not treat a bare non-identifier word as a claimed artifact`() {
        val text = """
            Delivery-Status: Shipped

            This is `just prose` with a backtick-quoted phrase, not code.
        """.trimIndent()
        val record = AdrTextScanner.parse("0100-example.md", text)
        assertThat(record!!.claimedArtifacts).isEmpty()
    }

    // Regression test for the ADR-0039 false positive (crying-wolf risk, HIGH-severity review
    // finding): a truncated illustrative GL-account-id example inside backticks used to be
    // captured as a claimed artifact purely because it contains a "/" — a false positive that
    // would fire a bogus CRITICAL SHIPPED_ARTIFACT_MISSING finding on a healthy, Shipped ADR the
    // very first time this scanner ran. The line below is copied verbatim from
    // docs/adr/0039-ledger-as-golden-source-balance-as-projection.md.
    @Test
    fun `does not treat an ellipsis-truncated example id as a claimed artifact (ADR-0039 shape)`() {
        val text = """
            Delivery-Status: Shipped

            double-entry `JournalEntry` lines against **GL control accounts only** — per-currency
            *deposit control* (`a0000000-…-2101/2102/2103`, CZK `…-0002`), *customer cash clearing*,
            and per-currency *FX position* accounts (`PaymentJournalFactory`).
        """.trimIndent()
        val record = AdrTextScanner.parse("0039-ledger-as-golden-source-balance-as-projection.md", text)
        val artifactNames = record!!.claimedArtifacts.map { it.name }
        assertThat(artifactNames).doesNotContain("a0000000-…-2101/2102/2103", "…-0002")
        // The real artifacts on the very same line must still be captured — the fix must not
        // over-exclude legitimate PascalCase references.
        assertThat(artifactNames).contains("JournalEntry", "PaymentJournalFactory")
    }

    @Test
    fun `does not treat an OpenAPI route template as a claimed artifact`() {
        val text = """
            Delivery-Status: Shipped

            The route `/api/svc/<service>` and `/api/v{N}` are illustrative path templates.
        """.trimIndent()
        val record = AdrTextScanner.parse("0048-api-contract.md", text)
        assertThat(record!!.claimedArtifacts).isEmpty()
    }

    // Broader verification, requested by the code review: parse the REAL ADR-0039 file from disk
    // (not a copied snippet) and confirm the fix holds against the actual, currently-merged text
    // on `main` — not just a hand-written regression fixture that could drift from reality.
    @Test
    fun `parsing the real ADR-0039 file on disk excludes the illustrative example and keeps real artifacts`() {
        val adr0039 = File(repoRoot(), "docs/adr/0039-ledger-as-golden-source-balance-as-projection.md")
        assertThat(adr0039).exists()
        val record = AdrTextScanner.parse(adr0039.name, adr0039.readText())
        assertThat(record).isNotNull
        assertThat(record!!.deliveryStatus).isEqualTo(AdrDeliveryStatus.SHIPPED)
        val artifactNames = record.claimedArtifacts.map { it.name }
        // No illustrative-marker token (ellipsis, angle bracket, brace, wildcard) leaked through.
        val illustrativeMarker = Regex("""[<>{}*…]|\.\.\.""")
        assertThat(artifactNames.filter { illustrativeMarker.containsMatchIn(it) }).isEmpty()
        // Real code artifacts the ADR legitimately names must still be captured.
        assertThat(artifactNames).contains(
            "LedgerService",
            "JournalEntry",
            "PaymentJournalFactory",
            "BalanceCoverPort",
            "BalanceService",
        )
    }

    /** Walks up from the test working directory to the monorepo root (the ancestor that has both
     * `settings.gradle.kts` and an `openbank-infra/` sibling directory), matching the idiom used
     * by SchemeAcceptedMsgOverrideConfigMapTest in openbank-transaction-service. */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "openbank-infra").isDirectory || !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error(
                    "Could not locate monorepo root (settings.gradle.kts + openbank-infra/) " +
                        "above ${System.getProperty("user.dir")}",
                )
        }
        return dir
    }
}
