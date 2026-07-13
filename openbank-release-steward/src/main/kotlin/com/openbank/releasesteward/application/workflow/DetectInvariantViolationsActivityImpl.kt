// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.releasesteward.domain.model.FindingSeverity
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.OpenApiPrChange
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import com.openbank.releasesteward.domain.model.ReleaseStewardSnapshot
import com.openbank.releasesteward.domain.model.RepoStateSnapshot
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
open class DetectInvariantViolationsActivityImpl : DetectInvariantViolationsActivity {

    override fun detect(snapshot: ReleaseStewardSnapshot): List<ReleaseStewardFinding> = buildList {
        addAll(checkManifestConfigLockstep(snapshot.repoState))
        addAll(checkAdminUiVersionSync(snapshot.repoState))
        addAll(checkAppVersionOverride(snapshot.repoState))
        addAll(checkOpenApiVersionCollisions(snapshot.openApiPrChanges))
    }

    // Mirrors openbank-infra/scripts/check-release-registration.py's exact set logic, run
    // proactively across the whole fleet rather than only against whatever a given PR touches
    // (ADR-0165 incident 1).
    private fun checkManifestConfigLockstep(state: RepoStateSnapshot): List<ReleaseStewardFinding> {
        val haveVersion = state.modulesWithVersionTxt
        val inConfig = state.releasePleaseConfigPackages
        val inManifest = state.releasePleaseManifestKeys
        return buildList {
            addAll(missingRegistration(haveVersion, inConfig, "release-please-config.json packages"))
            addAll(missingRegistration(haveVersion, inManifest, ".release-please-manifest.json"))
            addAll(orphanRegistration(haveVersion, inConfig, "release-please-config.json packages", "package entry"))
            addAll(orphanRegistration(haveVersion, inManifest, ".release-please-manifest.json", "manifest entry"))
            addAll(lockstepMismatch(inConfig, inManifest))
        }
    }

    private fun missingRegistration(
        haveVersion: Set<String>,
        registered: Set<String>,
        registrySource: String,
    ): List<ReleaseStewardFinding> = (haveVersion - registered).sorted().map { module ->
        newFinding(
            component = module,
            checkType = ReleaseInvariantCheckType.MANIFEST_CONFIG_LOCKSTEP,
            severity = FindingSeverity.CRITICAL,
            title = "$module has version.txt but is NOT in $registrySource — it will never get a " +
                "Release PR/changelog/tag (ADR-0165 incident 1)",
            rawMetricValue = BigDecimal.ZERO,
            threshold = BigDecimal.ONE,
        )
    }

    private fun orphanRegistration(
        haveVersion: Set<String>,
        registered: Set<String>,
        registrySource: String,
        entryNoun: String,
    ): List<ReleaseStewardFinding> = (registered - haveVersion).sorted().map { orphan ->
        newFinding(
            component = orphan,
            checkType = ReleaseInvariantCheckType.MANIFEST_CONFIG_LOCKSTEP,
            severity = FindingSeverity.WARNING,
            title = "$orphan is in $registrySource but has no version.txt — either add version.txt " +
                "or remove the $entryNoun",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
        )
    }

    private fun lockstepMismatch(inConfig: Set<String>, inManifest: Set<String>): List<ReleaseStewardFinding> =
        ((inConfig - inManifest) + (inManifest - inConfig)).sorted().map { diff ->
            val side = if (diff in inConfig) "config but not manifest" else "manifest but not config"
            newFinding(
                component = diff,
                checkType = ReleaseInvariantCheckType.MANIFEST_CONFIG_LOCKSTEP,
                severity = FindingSeverity.CRITICAL,
                title = "$diff is in release-please $side — the two must stay in lockstep " +
                    "(ADR-0165 incident 1)",
                rawMetricValue = BigDecimal.ZERO,
                threshold = BigDecimal.ONE,
            )
        }

    // Mirrors .github/scripts/check-admin-ui-version-sync.sh (ADR-0165 incident 2).
    private fun checkAdminUiVersionSync(state: RepoStateSnapshot): List<ReleaseStewardFinding> {
        val pkg = state.adminUiPackageJsonVersion
        val txt = state.adminUiVersionTxt
        if (pkg == null || txt == null || pkg == txt) return emptyList()
        return listOf(
            newFinding(
                component = "openbank-admin-ui",
                checkType = ReleaseInvariantCheckType.ADMIN_UI_VERSION_SYNC,
                severity = FindingSeverity.WARNING,
                title = "admin-ui version drift: version.txt=$txt != package.json=$pkg " +
                    "(ADR-0165 incident 2)",
                rawMetricValue = BigDecimal.ZERO,
                threshold = BigDecimal.ONE,
            ),
        )
    }

    // Mirrors .github/scripts/check-app-version-override.sh, run proactively across the whole
    // fleet rather than only on whichever service the next PR happens to touch (ADR-0165
    // incident 3).
    private fun checkAppVersionOverride(state: RepoStateSnapshot): List<ReleaseStewardFinding> =
        state.servicesWithVersionOverride.sorted().map { service ->
            newFinding(
                component = service,
                checkType = ReleaseInvariantCheckType.APP_VERSION_OVERRIDE,
                severity = FindingSeverity.CRITICAL,
                title = "$service sets quarkus.application.version explicitly in application.yaml " +
                    "— shadows the build-stamped version.txt value (ADR-0165 incident 3, " +
                    "rules.yaml: release_invariant)",
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
            )
        }

    // New capability no existing CI gate has (ADR-0165 incident 4): check-api-contract.py only
    // ever compares one PR's head against its own merge base, so it is structurally blind to a
    // competing open PR bumping the same spec. This activity queries every open PR at once.
    private fun checkOpenApiVersionCollisions(prChanges: List<OpenApiPrChange>): List<ReleaseStewardFinding> =
        prChanges.groupBy { it.service }
            .filterValues { changes -> changes.map { it.prNumber }.distinct().size > 1 }
            .map { (service, changes) ->
                val first = changes.first()
                val prNumbers = changes.map { it.prNumber }.distinct().sorted()
                val versions = changes.map { it.proposedInfoVersion }.distinct()
                val detail = if (versions.size == 1) {
                    "and propose the IDENTICAL info.version ${versions.first()} — a direct collision"
                } else {
                    "with different proposed versions (${versions.joinToString()}); whichever merges " +
                        "first invalidates the other's diff base (check-api-contract.py is diff-base-blind " +
                        "to this race)"
                }
                newFinding(
                    component = "$service/openapi.yaml",
                    checkType = ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION,
                    severity = FindingSeverity.CRITICAL,
                    title = "${prNumbers.size} open PRs (${prNumbers.joinToString { "#$it" }}) touch " +
                        "$service/openapi.yaml concurrently $detail (ADR-0165 incident 4)",
                    prNumber = first.prNumber,
                    prUrl = first.prUrl,
                    rawMetricValue = BigDecimal.valueOf(prNumbers.size.toLong()),
                    threshold = BigDecimal.ONE,
                )
            }

    private fun newFinding(
        component: String,
        checkType: ReleaseInvariantCheckType,
        severity: FindingSeverity,
        title: String,
        rawMetricValue: BigDecimal,
        threshold: BigDecimal,
        prNumber: Int? = null,
        prUrl: String? = null,
    ) = ReleaseStewardFinding(
        id = Ids.newId().toString(),
        checkType = checkType,
        severity = severity,
        detectedAt = Instant.now(),
        title = title,
        component = component,
        prNumber = prNumber,
        prUrl = prUrl,
        rawMetricValue = rawMetricValue,
        threshold = threshold,
        status = FindingStatus.OPEN,
    )
}
