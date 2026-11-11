// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.monitoring.MonitoringEvaluator
import com.openbank.aml.application.monitoring.MonitoringPack
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.out.MonitoringEvaluationRepository
import com.openbank.aml.application.port.out.MovementCounterReader
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.aml.domain.monitoring.MonitoringEvaluationRecord
import com.openbank.aml.domain.monitoring.MonitoringHit
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Evaluates the monitoring pack for every party with recent counted movement (ADR-0371 §3, §4, §9).
 *
 * SHADOW by default: a hit is recorded in `aml_monitoring_hit` and nothing else happens. Only when
 * `openbank.aml.monitoring.evaluation.open-cases` is true does a hit open an `AmlCase` of type
 * `TRANSACTION_MONITORING`, carrying the pack hash, the scenario code and the counter snapshot. The
 * case key is party, scenario and window, so a repeat detection finds the same case.
 *
 * Every evaluated party gets an outcome row. A pack that could not be evaluated is stored as
 * INCOMPLETE with the interpreter's reason; it is never stored, or counted, as "nothing found".
 *
 * Liveness is recorded only by a tick that got through every active party. The alert that matters
 * is "evaluating nothing while money moves", so a tick that throws must leave the gauge ageing.
 *
 * `suspend`, never `runBlocking`: a plain `@Scheduled` method has no Vert.x context.
 */
@Startup
@ApplicationScoped
class MonitoringEvaluationScheduler {

    @Inject
    lateinit var pack: MonitoringPack

    @Inject
    lateinit var scheme: CounterScheme

    @Inject
    lateinit var reader: MovementCounterReader

    @Inject
    lateinit var repository: MonitoringEvaluationRepository

    @Inject
    lateinit var cases: AmlCaseUseCase

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @ConfigProperty(name = "openbank.aml.monitoring.evaluation.enabled", defaultValue = "true")
    var enabled: Boolean = true

    @ConfigProperty(name = "openbank.aml.monitoring.evaluation.open-cases", defaultValue = "false")
    var openCases: Boolean = false

    @ConfigProperty(name = "openbank.aml.monitoring.evaluation.page-size", defaultValue = "200")
    var pageSize: Int = DEFAULT_PAGE_SIZE

    private val log = Logger.getLogger(MonitoringEvaluationScheduler::class.java)
    private val oldestUndecidedAgeSeconds = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null
    private lateinit var evaluator: MonitoringEvaluator

    @PostConstruct
    fun register() {
        evaluator = MonitoringEvaluator(pack, scheme, reader)
        if (registryInstance.isResolvable) {
            Gauge.builder("openbank.aml.monitoring.cases.oldest_undecided_age_seconds", oldestUndecidedAgeSeconds) {
                it.get().toDouble()
            }.tag("service", "aml").strongReference(true).register(registryInstance.get())
        }
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        log.infof("[monitoring-evaluation] mode=%s", if (openCases) "CASES" else "SHADOW")
    }

    @Scheduled(
        cron = "{openbank.aml.monitoring.evaluation.cron:0 3/15 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun tick() {
        if (!enabled) return
        val now = clock.instant()
        val since = scheme.bucketStart(now.minusSeconds(pack.longestWindowSeconds))
        var cursor: UUID? = null
        var evaluated = 0
        while (true) {
            val page = repository.activeParties(since, cursor, pageSize)
            for (partyId in page) {
                process(evaluator.evaluate(partyId, now), now)
                evaluated++
            }
            if (page.size < pageSize) break
            cursor = page.last()
        }
        oldestUndecidedAgeSeconds.set(
            repository.oldestUndecidedMonitoringCase()?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0,
        )
        if (evaluated > 0) log.infof("[monitoring-evaluation] evaluated %d party(ies)", evaluated)
        liveness?.recordSuccess()
    }

    private suspend fun process(record: MonitoringEvaluationRecord, now: Instant) {
        repository.recordEvaluation(record)
        count(METRIC_EVALUATIONS, "outcome", record.outcome.name)
        for (hit in record.hits) {
            val snapshot = objectMapper.writeValueAsString(snapshot(hit, record))
            val recorded = repository.recordHit(hit, record.pack, snapshot, now)
            if (recorded.created) count(METRIC_HITS, "scenario", hit.scenarioCode)
            if (openCases && recorded.caseId == null) {
                repository.markCaseOpened(recorded.hitId, openCase(hit, snapshot).id)
                count(METRIC_CASES, "scenario", hit.scenarioCode)
            }
        }
    }

    private suspend fun openCase(hit: MonitoringHit, snapshot: String) = cases.createCase(
        CreateAmlCaseCommand(
            // One case per party, scenario and window: createCase returns the existing one for this key.
            idempotencyKey = "tm:${hit.partyId}:${hit.scenarioCode}:${hit.windowStart.epochSecond}",
            partyId = hit.partyId,
            accountId = null,
            transactionId = null,
            customerReference = "monitoring-${hit.partyId}",
            screeningType = ScreeningType.TRANSACTION_MONITORING,
            riskLevel = pack.severityOf(hit.scenarioCode),
            alertCode = hit.scenarioCode,
            alertDetail = snapshot,
            matchedEntity = null,
        ),
    )

    private fun snapshot(hit: MonitoringHit, record: MonitoringEvaluationRecord): Map<String, Any?> = mapOf(
        "packId" to record.pack.id,
        "packRevision" to record.pack.revision,
        "packHash" to record.pack.contentHash,
        "scenarioCode" to hit.scenarioCode,
        "reasonCode" to hit.reasonCode,
        "inputHash" to record.inputHash,
        "evaluatedAt" to record.evaluatedAt.toString(),
        "counter" to mapOf(
            "featureId" to hit.observation.featureId,
            "currency" to hit.observation.currency,
            "band" to hit.observation.band,
            "direction" to hit.observation.direction?.name,
            "rail" to hit.observation.rail,
            "windowFrom" to hit.observation.windowFrom.toString(),
            "windowTo" to hit.observation.windowTo.toString(),
            "count" to hit.observation.count,
        ),
    )

    private fun count(metric: String, tag: String, value: String) {
        if (registryInstance.isResolvable) registryInstance.get().counter(metric, tag, value).increment()
    }

    companion object {
        const val WORKFLOW_NAME = "aml-monitoring-evaluation"
        const val METRIC_EVALUATIONS = "openbank.aml.monitoring.evaluations"
        const val METRIC_HITS = "openbank.aml.monitoring.hits"
        const val METRIC_CASES = "openbank.aml.monitoring.cases_opened"
        private const val DEFAULT_PAGE_SIZE = 200
        private val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
