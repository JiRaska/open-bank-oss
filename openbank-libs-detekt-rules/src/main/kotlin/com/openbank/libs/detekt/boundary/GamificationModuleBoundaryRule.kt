// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.boundary

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

private val FORBIDDEN_IMPORTER_PACKAGE_PREFIXES = listOf(
    "com.openbank.lending",
    "com.openbank.decisioning",
)
private const val GAMIFICATION_PACKAGE_INFIX = ".gamification."
private const val GAMIFICATION_PACKAGE_SUFFIX = ".gamification"

/**
 * ADR-0220 D3's structural safety invariant #6: a lending or credit-decisioning module must never
 * import gamification domain state. ADR-0220's own "Alternatives considered" rejects "gamify
 * engagement with credit products (take a loan, earn points)" *absolutely* — "the textbook
 * mis-selling pattern, and no conversion figure justifies a conduct finding" — and D3 rule 1
 * separately forbids any challenge rewarding credit uptake. Those are both policy statements about
 * what the gamification *catalogue* may contain ([EarnSource]'s closed sealed hierarchy already
 * makes that one a compile error). This rule closes the other half: even a correctly-scoped
 * gamification module must stay unreachable from the credit-decisioning side, so a future feature
 * cannot quietly read a party's `Points`/`Streak`/`Badge` state INTO a lending or decisioning
 * eligibility calculation — which would recreate the same conduct risk from the opposite
 * direction, with no catalogue entry to review it against.
 *
 * As of this rule landing, no fleet module can literally import
 * `com.openbank.engagement.domain.model.gamification` today — each `openbank-*-service` is its
 * own Gradle module with no compile dependency on another service's module, only on the shared
 * `openbank-libs-domain`/`openbank-libs-runtime`. This rule is deliberately preventive, not
 * reactive: it is the guard that fires the day gamification types are ever promoted into a shared
 * module (making them importable at all), or a service is renamed/restructured such that a
 * `com.openbank.lending`/`com.openbank.decisioning` package gains a real compile-time path to
 * them. Config's own detekt sees every module's sources per its own forked CLI run
 * (`openbank.static-analysis.gradle.kts`), so the check is real per-module today even though the
 * import path it looks for is presently unreachable everywhere.
 *
 * Text-based, like [com.openbank.libs.detekt.contact.MarketingCallSiteWiringRule] — matches on the
 * fully-qualified import path rather than resolved types (`RequiresTypeResolution` is avoided
 * fleet-wide; see that rule's KDoc for why), so it fires for anything under a package ending in
 * `.gamification` or containing `.gamification.`, imported from a file whose own package starts
 * with one of [FORBIDDEN_IMPORTER_PACKAGE_PREFIXES].
 */
class GamificationModuleBoundaryRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "A lending/credit-decisioning module must not import gamification domain state " +
            "(ADR-0220 D3) — reward mechanics and credit decisions must stay structurally separate.",
        Debt.TWENTY_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val filePackage = file.packageFqName.asString()
        val isForbiddenImporter = FORBIDDEN_IMPORTER_PACKAGE_PREFIXES.any {
            filePackage == it || filePackage.startsWith("$it.")
        }
        if (!isForbiddenImporter) return

        file.importDirectives.forEach { import -> checkImport(import, filePackage) }
    }

    private fun checkImport(import: KtImportDirective, filePackage: String) {
        val importPath = import.importedFqName?.asString() ?: return
        val importsGamification = importPath.contains(GAMIFICATION_PACKAGE_INFIX) ||
            importPath.endsWith(GAMIFICATION_PACKAGE_SUFFIX)
        if (!importsGamification) return

        report(
            CodeSmell(
                issue,
                Entity.from(import),
                "'$filePackage' imports '$importPath' — a lending/credit-decisioning module must " +
                    "not depend on gamification domain state (ADR-0220 D3).",
            ),
        )
    }
}
