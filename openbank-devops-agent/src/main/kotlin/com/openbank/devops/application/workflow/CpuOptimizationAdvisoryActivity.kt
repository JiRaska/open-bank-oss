// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.domain.model.CiGateProfileEvidence
import com.openbank.devops.domain.model.CpuOptimizationAdvisoryPolicy
import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RemediationKind
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.activity.ActivityInterface
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ActivityInterface
interface CpuOptimizationAdvisoryActivity {
    fun assess(profile: CiGateProfileEvidence): DevOpsFinding?
}

/** A deterministic measurement request, not an inferred defect, saving, or implementation grant. */
@ApplicationScoped
open class CpuOptimizationAdvisoryActivityImpl(
    private val repository: FindingRepository,
    private val mapper: ObjectMapper,
) : CpuOptimizationAdvisoryActivity {
    override fun assess(profile: CiGateProfileEvidence): DevOpsFinding? = runOnVertxContext {
        val now = Instant.now()
        val gate = CpuOptimizationAdvisoryPolicy.select(profile, now) ?: return@runOnVertxContext null
        val provenance = requireNotNull(profile.provenance)
        val identity = listOf(
            "cpu-advisory-v1",
            profile.repository,
            provenance.sourceSha,
            provenance.runId.toString(),
            provenance.runAttempt.toString(),
            provenance.artifactDigest,
            gate.id,
        ).joinToString("\u0000")
        val finding = DevOpsFinding(
            id = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString(),
            detector = DetectorId.D1_CI_PIPELINE_HEALTH,
            severity = FindingSeverity.WARNING,
            detectedAt = profile.observedAt,
            title = "Measure CPU optimization candidate: ${gate.id}",
            rawMetricValue = BigDecimal.valueOf(gate.cpuSeconds),
            threshold = BigDecimal.ZERO,
            affectedResource = "ci-gate:${gate.id}",
            rootCause = mapper.writeValueAsString(
                mapOf(
                    "scope" to "SINGLE_ARTIFACT",
                    "repository" to profile.repository,
                    "observedAt" to profile.observedAt,
                    "provenance" to provenance,
                    "gate" to gate,
                ),
            ),
            remediationKind = RemediationKind.NONE,
            proposedRemediation = "Observed CPU only; no proven saving or pipeline-wide ranking. " +
                "Before an implementation PR: verify current source and ownership; run alternating A/B " +
                "pairs of the actual whole gate with identical corpus, verdicts, raw diagnostics and " +
                "provenance; include setup, new tests and cleanup; measure total process-tree CPU, " +
                "wall and process/request counts. Reject a net regression. Preserve coverage, " +
                "security, contracts, timeouts and runner count. Require independent review and " +
                "human merge/deploy approval. No implementation or GitHub write is authorized here.",
            status = FindingStatus.DIAGNOSED,
            diagnosedAt = now,
        )
        repository.saveAdvisoryIfAbsent(finding)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
