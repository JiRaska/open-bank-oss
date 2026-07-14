// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.TestScanSnapshot
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
open class DetectDriftActivityImpl : DetectDriftActivity {

    override fun detect(snapshot: TestScanSnapshot): List<FlakyTestFinding> = buildList {
        addAll(checkRunBlockingUnitMissing(snapshot))
        addAll(checkPactLocalVerificationBlindSpot(snapshot))
        addAll(checkPactProviderClassCollision(snapshot))
        addAll(checkTestCountDrift(snapshot))
    }

    // Check 1 (ADR-0168, generalizes .github/scripts/check-test-runblocking-unit.sh's exact
    // regex, proven to fire pre-merge for real — PR #931, PR #994): an expression-body function in
    // test source whose last expression is a coroutine builder without an explicit `: Unit` return
    // type infers a non-Unit type, so JUnit5 silently drops it as a test method instead of running
    // it. Every match is CRITICAL — a dropped test is a false "all green," not a lint nit.
    private fun checkRunBlockingUnitMissing(snapshot: TestScanSnapshot): List<FlakyTestFinding> =
        snapshot.runBlockingViolations.map { violation ->
            newFinding(
                checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
                severity = FindingSeverity.CRITICAL,
                title = "${violation.file}:${violation.line} uses the unsafe '= ${violation.builder} {' " +
                    "expression-body form without an explicit ': Unit' return type — JUnit5 silently drops " +
                    "this test method instead of running it (the same shape check-test-runblocking-unit.sh " +
                    "guards against for 'runBlocking'; '${violation.builder}' is not covered by that literal " +
                    "pattern)",
                component = violation.file,
                filePath = violation.file,
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
            )
        }

    // Check 2 (ADR-0168, CLAUDE.md "Contract tests (Pact) pitfalls"): a Pact provider verification
    // test gated on @EnabledIfSystemProperty(named = "pactbroker.url", ...) is always skipped when a
    // developer runs `./gradlew test` locally — nothing tells them their all-green run never actually
    // verified those contracts. One WARNING finding per module, not per class, so the report reads as
    // "this module has a local-verification blind spot," not N near-duplicate findings.
    private fun checkPactLocalVerificationBlindSpot(snapshot: TestScanSnapshot): List<FlakyTestFinding> =
        snapshot.pactGatedClasses
            .groupBy { it.module }
            .map { (module, classes) ->
                val sample = classes.take(SAMPLE_LIMIT).joinToString(", ") { it.className }
                newFinding(
                    checkType = FlakyTestCheckType.PACT_LOCAL_VERIFICATION_BLIND_SPOT,
                    severity = FindingSeverity.WARNING,
                    title = "$module has ${classes.size} Pact provider verification test(s) gated on " +
                        "pactbroker.url ($sample) — always skipped locally; a green './gradlew test' run in " +
                        "this module never verified these contracts, only CI (against a real broker) does",
                    component = module,
                    filePath = classes.first().file,
                    rawMetricValue = BigDecimal(classes.size),
                    threshold = BigDecimal.ZERO,
                )
            }

    // Check 3 (ADR-0168, CLAUDE.md "Contract tests (Pact) pitfalls" — the transaction-service
    // two-provider-class collision): two or more distinct test classes declaring @Provider("X") for
    // the SAME provider name both pull every pact the broker holds for that provider; the fix is one
    // @Provider test picking the target per interaction in @BeforeEach, not two separate classes.
    private fun checkPactProviderClassCollision(snapshot: TestScanSnapshot): List<FlakyTestFinding> =
        snapshot.pactProviderDeclarations
            .groupBy { it.providerName }
            .filter { (_, declarations) -> declarations.map { it.file }.distinct().size > 1 }
            .map { (providerName, declarations) ->
                val files = declarations.map { it.file }.distinct().sorted()
                newFinding(
                    checkType = FlakyTestCheckType.PACT_PROVIDER_CLASS_COLLISION,
                    severity = FindingSeverity.CRITICAL,
                    title = "Provider '$providerName' has ${files.size} separate @Provider test classes " +
                        "(${files.joinToString(", ")}) — each pulls EVERY pact the broker holds for this " +
                        "provider; a class targeting HttpTestTarget then also tries (and fails) to verify a " +
                        "message pact meant for a MessageTestTarget class, or vice versa. Fix: one @Provider " +
                        "test that selects the target per interaction in @BeforeEach",
                    component = providerName,
                    filePath = files.first(),
                    rawMetricValue = BigDecimal(files.size),
                    threshold = BigDecimal.ONE,
                )
            }

    // Check 4 (ADR-0168): an independent cross-check, not a re-detector for check 1 — compares the
    // number of @Test-annotated functions declared in a module's source against the number JUnit
    // actually reports as executed (build/test-results/test/*.xml). Only flags executedCount <
    // declaredCount (a real drop); executedCount > declaredCount is common and benign (parameterized/
    // dynamic tests expand into more reported cases than source-level @Test occurrences) and is
    // deliberately not flagged, to avoid manufacturing false positives on every parameterized suite.
    private fun checkTestCountDrift(snapshot: TestScanSnapshot): List<FlakyTestFinding> = snapshot.testCountSamples
        .filter { it.executedCount < it.declaredCount }
        .map { sample ->
            newFinding(
                checkType = FlakyTestCheckType.TEST_COUNT_DRIFT,
                severity = FindingSeverity.CRITICAL,
                title = "${sample.module} declares ${sample.declaredCount} @Test function(s) in source but " +
                    "JUnit only reported ${sample.executedCount} executed — something is silently skipping " +
                    "or filtering tests (could be the runBlocking-Unit shape, a stale JUnit5 tag filter, or " +
                    "an unrelated cause; this check only proves the count mismatch, not the root cause)",
                component = sample.module,
                filePath = "${sample.module}/build/test-results/test",
                rawMetricValue = BigDecimal(sample.executedCount),
                threshold = BigDecimal(sample.declaredCount),
            )
        }

    private fun newFinding(
        checkType: FlakyTestCheckType,
        severity: FindingSeverity,
        title: String,
        component: String,
        filePath: String,
        rawMetricValue: BigDecimal,
        threshold: BigDecimal,
    ) = FlakyTestFinding(
        id = Ids.newId().toString(),
        checkType = checkType,
        severity = severity,
        detectedAt = Instant.now(),
        title = title,
        component = component,
        filePath = filePath,
        rawMetricValue = rawMetricValue,
        threshold = threshold,
        status = FindingStatus.OPEN,
    )

    private companion object {
        const val SAMPLE_LIMIT = 3
    }
}
