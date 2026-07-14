// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.port.out

import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.TestScanSnapshot

/**
 * Direct repo-checkout grep/text-scan read of every service's `src/test/kotlin` Kotlin test tree
 * fleet-wide, and each module's `build/test-results/test` JUnit XML reports (`read.governance`,
 * ADR-0168 checks 1-4). The check-1 regex is deliberately kept textually consistent with
 * `.github/scripts/check-test-runblocking-unit.sh`'s own pattern (same anchor, same `: Unit`
 * exclusion) rather than parsing that script at runtime, so the two stay in sync by construction —
 * a change to one without the other is a review-time diff, not a silent divergence. Grep/text-scan
 * based, not a Kotlin compiler-frontend/PSI parse (ADR-0168 Decision) — this agent runs from within
 * the monorepo, so a full re-implementation of a code-intelligence index is not needed for these
 * four checks; mirrors authz-policy-auditor's `PolicyScanPort` / docs-truth-agent's `RepoScanPort` /
 * release-steward's `RepoStateReadPort` precedent of real, best-effort file/grep logic rather than a
 * stub.
 */
interface TestScanPort {
    /** A single repo-checkout pass gathering every raw signal the four implemented checks need —
     * batched rather than one call per check so the whole fleet's test source is walked once per
     * run. */
    suspend fun scan(): TestScanSnapshot
}

interface LlmDiagnosisPort {
    suspend fun diagnose(finding: FlakyTestFinding, contextMetrics: Map<String, Double>): String

    /** Returns non-null ONLY when it might, in a future iteration, generate a scaffold diff for
     * `TEST_COUNT_DRIFT` — the one check with a shape that COULD have a narrow mechanical case (e.g.
     * a stale JUnit5 tag-filter exclusion). Every other check type always returns null BY DESIGN
     * (ADR-0168 Decision): guessing wrong on `suspend fun` vs. `: Unit` for a runBlocking-Unit
     * finding, or on which of two colliding `@Provider` test classes should own the interaction, or
     * on why a Pact provider test is locally gated, could silently mask a real bug instead of fixing
     * it — a human has to read the test's intent first. In v1 this still returns null for every
     * check type (the fleet's usual bootstrap-stub state), pending the shared LiteLLM/GitHub App
     * wiring; the branch point is kept structurally in place, mirroring release-steward's
     * `APP_VERSION_OVERRIDE` precedent. */
    suspend fun proposeFixDiff(finding: FlakyTestFinding, diagnosis: String): String?
}

interface GitHubProposalPort {
    /** Rarely called in v1 — see [LlmDiagnosisPort.proposeFixDiff]. Kept for interface parity with
     * every sibling agent's [GitHubProposalPort] shape. */
    suspend fun openProposalPr(finding: FlakyTestFinding, fixDiff: String): String

    /** The default disposition path: a flaky or silently-skipped test needs a human to read the
     * test's intent before anything changes (ADR-0168 Decision) — every finding from checks 1-3,
     * and almost every `TEST_COUNT_DRIFT` finding too, ends up here. */
    suspend fun openTicket(finding: FlakyTestFinding, diagnosis: String): String
}

interface FindingRepository {
    suspend fun save(finding: FlakyTestFinding): FlakyTestFinding
    suspend fun findActive(): List<FlakyTestFinding>
    suspend fun findById(id: String): FlakyTestFinding?
    suspend fun update(finding: FlakyTestFinding): FlakyTestFinding
}
