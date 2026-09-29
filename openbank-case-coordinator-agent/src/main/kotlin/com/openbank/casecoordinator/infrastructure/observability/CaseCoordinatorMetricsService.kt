// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.observability

import com.openbank.libs.observability.standardPercentiles
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@ApplicationScoped
class CaseCoordinatorMetricsService {

    @Inject
    lateinit var registry: MeterRegistry

    private lateinit var activeCasesGauge: MultiGauge
    private val killSwitchActiveByScope = ConcurrentHashMap<String, AtomicLong>()

    @PostConstruct
    fun init() {
        activeCasesGauge = MultiGauge.builder("openbank.casecoordinator.active_cases")
            .description("Number of case-coordinator cases currently in the database by class and status")
            .register(registry)
    }

    fun updateActiveCases(counts: Map<Pair<String, String>, Int>) {
        val rows = counts.map { (key, count) ->
            MultiGauge.Row.of(
                Tags.of("caseClass", key.first.lowercase(), "status", key.second.lowercase()),
                count,
            )
        }
        activeCasesGauge.register(rows, true)
    }

    fun recordKillSwitchEvent(scope: String, eventType: String) {
        registry.counter(
            "openbank.casecoordinator.kill_switch.events",
            "scope",
            scope,
            "eventType",
            eventType,
        ).increment()
    }

    fun setKillSwitchActive(scope: String, active: Boolean) {
        val ref = killSwitchActiveByScope.computeIfAbsent(scope) { s ->
            val value = AtomicLong(0L)
            Gauge.builder("openbank.casecoordinator.kill_switch.active") { value.get() }
                .description("Whether a kill-switch is currently active for the scope")
                .tag("scope", s)
                .strongReference(true)
                .register(registry)
            value
        }
        ref.set(if (active) 1L else 0L)
    }

    private val haltLatencyTimers = ConcurrentHashMap<Pair<String, String>, Timer>()

    fun recordHaltLatency(caseClass: String, deliveryMode: String, latencyMs: Long) {
        val key = caseClass.lowercase() to deliveryMode.lowercase()
        val timer = haltLatencyTimers.computeIfAbsent(key) { (cc, dm) ->
            Timer.builder("openbank.casecoordinator.halt_latency_seconds")
                .description("Time from kill-switch event to case halt confirmation")
                .standardPercentiles()
                .serviceLevelObjectives(
                    Duration.ofSeconds(SIXTY_SECONDS),
                    Duration.ofSeconds(ONE_HUNDRED_TWENTY_SECONDS),
                    Duration.ofSeconds(ONE_HUNDRED_EIGHTY_SECONDS),
                    Duration.ofSeconds(TWO_HUNDRED_FORTY_SECONDS),
                    Duration.ofSeconds(THREE_HUNDRED_SECONDS),
                    Duration.ofSeconds(SIX_HUNDRED_SECONDS),
                    Duration.ofSeconds(NINE_HUNDRED_SECONDS),
                )
                .tags("caseClass", cc, "deliveryMode", dm)
                .register(registry)
        }
        timer.record(latencyMs, TimeUnit.MILLISECONDS)
    }

    fun recordCaseOpened(caseClass: String, deliveryMode: String) {
        registry.counter(
            "openbank.casecoordinator.cases_opened",
            "caseClass",
            caseClass.lowercase(),
            "deliveryMode",
            deliveryMode.lowercase(),
        ).increment()
    }

    private companion object {
        const val SIXTY_SECONDS: Long = 60
        const val ONE_HUNDRED_TWENTY_SECONDS: Long = 120
        const val ONE_HUNDRED_EIGHTY_SECONDS: Long = 180
        const val TWO_HUNDRED_FORTY_SECONDS: Long = 240
        const val THREE_HUNDRED_SECONDS: Long = 300
        const val SIX_HUNDRED_SECONDS: Long = 600
        const val NINE_HUNDRED_SECONDS: Long = 900
    }
}
