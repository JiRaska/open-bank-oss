// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.retention

import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Enforces the KYC case hard-delete retention policy (ADR-0118 §5).
 *
 * When a party is erased (GDPR Art. 17), [anonymizeByPartyId] stamps `erased_at` on every KYC
 * case for that party. This scheduler then deletes the full row once the AML-mandated 5-year
 * hold period has expired. Deleting before 5 years would violate AML Act §16.
 *
 * Run daily at 03:30 UTC (configurable). `concurrentExecution = SKIP` prevents overlap.
 */
@ApplicationScoped
class KycRetentionScheduler(
    private val kycCaseRepository: KycCaseRepository,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.retention.kyc.retention-years", defaultValue = "5")
    private val retentionYears: Long,
    @ConfigProperty(name = "openbank.retention.kyc.dry-run", defaultValue = "false")
    private val dryRun: Boolean,
    @ConfigProperty(name = "openbank.retention.kyc.enabled", defaultValue = "true")
    private val enabled: Boolean,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(KycRetentionScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Registers a boot-seeded heartbeat (ADR-0237) before the first daily tick. A disabled or
     * dry-run sweep deliberately does not record success: neither proves the retention delete ran.
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "{openbank.retention.kyc.cron:0 30 3 * * ?}",
        concurrentExecution = SKIP,
    )
    suspend fun enforceRetention() {
        if (!enabled) return

        // Use calendar-year subtraction (minusYears), not 365-day arithmetic, to correctly handle
        // leap years — otherwise cutoff lands 1-2 days early and violates the AML §16 5-year hold.
        val cutoff = Instant.now(clock)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .minusYears(retentionYears)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()

        if (dryRun) {
            log.infof(
                "[retention] DRY-RUN: would delete KYC cases with erased_at < %s (retention %dy)",
                cutoff,
                retentionYears,
            )
            return
        }

        val count = kycCaseRepository.deleteErasedCasesOlderThan(cutoff)
        liveness?.recordSuccess()
        if (count > 0) {
            log.infof(
                "[retention] Deleted %d KYC case(s) with erased_at < %s (ADR-0118 §5, AML Act §16)",
                count,
                cutoff,
            )
        }
    }

    private companion object {
        const val WORKFLOW_NAME = "kyc-retention"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
