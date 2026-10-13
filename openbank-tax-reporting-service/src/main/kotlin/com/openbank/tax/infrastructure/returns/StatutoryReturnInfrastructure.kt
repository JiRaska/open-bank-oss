// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.tax.application.port.out.ReturnCatalogueSource
import com.openbank.tax.application.port.out.ReturnWireRendererPort
import com.openbank.tax.application.port.out.StatutoryReturnMetricsPort
import com.openbank.tax.application.usecase.ReportingEntities
import com.openbank.tax.application.usecase.StatutoryReturnService
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnDefinition
import com.openbank.tax.domain.returns.ReturnScope
import com.openbank.tax.domain.returns.SignedTerm
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.domain.returns.ValidationRule
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads the jurisdiction catalogues named in `openbank.statutory-returns.catalogues` from the
 * classpath. Parsed once and validated by the domain constructors — a malformed catalogue fails
 * boot, not the first filing.
 */
@ApplicationScoped
class ClasspathReturnCatalogueSource(
    @ConfigProperty(name = "openbank.statutory-returns.catalogues") resources: List<String>,
    objectMapper: ObjectMapper,
) : ReturnCatalogueSource {
    private val loaded: List<ReturnCatalogue> = resources.map { path -> CatalogueParser.parse(objectMapper, path) }

    override fun catalogues(): List<ReturnCatalogue> = loaded
}

/** JSON → domain. Kept separate so tests can parse the shipped resource without CDI. */
object CatalogueParser {
    fun parse(objectMapper: ObjectMapper, resource: String): ReturnCatalogue {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
            ?: CatalogueParser::class.java.classLoader.getResourceAsStream(resource)
            ?: error("Return catalogue resource not found: $resource")
        val root = stream.use { objectMapper.readTree(it) }
        return ReturnCatalogue(
            id = root.text("id"),
            version = root.required("version").asInt(),
            jurisdiction = root.text("jurisdiction"),
            wireFormatVerified = root.required("wireFormatVerified").asBoolean(),
            returns = root.required("returns").map { it.toDefinition() },
        )
    }

    private fun JsonNode.toDefinition() = ReturnDefinition(
        code = text("code"),
        name = text("name"),
        scope = ReturnScope.valueOf(text("scope")),
        periodicity = Periodicity.valueOf(text("periodicity")),
        deadlineDaysAfterPeriodEnd = required("deadlineDaysAfterPeriodEnd").asInt(),
        datapoints = required("datapoints").map { it.asText() },
        rules = (get("rules") ?: emptyList<JsonNode>()).map { it.toRule() },
        legalBasis = text("legalBasis"),
    )

    private fun JsonNode.toRule(): ValidationRule = when (val type = text("type")) {
        "NON_NEGATIVE" -> ValidationRule.NonNegative(text("id"), required("datapoints").map { it.asText() })
        "SUM_EQUALS" -> ValidationRule.SumEquals(
            text("id"),
            text("target"),
            required("terms").map { SignedTerm(it.text("datapoint"), it.required("sign").asInt()) },
        )
        else -> error("Unknown validation rule type '$type'")
    }

    private fun JsonNode.required(field: String): JsonNode = get(field) ?: error("Catalogue field '$field' missing")

    private fun JsonNode.text(field: String): String = required(field).asText()
}

@ApplicationScoped
class UnavailableReturnWireRenderer : ReturnWireRendererPort {
    override val available: Boolean = false

    override suspend fun render(statutoryReturn: StatutoryReturn): ByteArray =
        throw UnsupportedOperationException("Regulator wire-format rendering is not built (ADR-0336 D5)")
}

/** Declared reporting entities. Funds are licensed and few, so an operator-declared list is the registry. */
@ApplicationScoped
class ReportingEntitiesProducer {
    @Produces
    @Singleton
    fun reportingEntities(
        @ConfigProperty(name = "openbank.statutory-returns.company-id", defaultValue = "company") companyId: String,
        @ConfigProperty(name = "openbank.statutory-returns.fund-ids") fundIds: Optional<List<String>>,
        @ConfigProperty(name = "openbank.statutory-returns.reporting-start") reportingStart: Optional<String>,
    ): ReportingEntities = ReportingEntities(
        companyId = companyId,
        fundIds = fundIds.orElse(emptyList()).map { it.trim() }.filter { it.isNotEmpty() },
        reportingStart = reportingStart.map { LocalDate.parse(it) }.orElse(null),
    )
}

@ApplicationScoped
class MicrometerStatutoryReturnMetrics(registry: MeterRegistry) : StatutoryReturnMetricsPort {
    private val breaches = AtomicInteger(0)

    init {
        Gauge.builder("openbank_statutory_return_overdue", breaches) { it.get().toDouble() }
            .description("Statutory returns past their deadline without a SUBMITTED revision (ADR-0336)")
            .register(registry)
    }

    override fun recordBreaches(count: Int) = breaches.set(count)
}

/** Refreshes the breach gauge. `suspend` so the reactive repository has a Vert.x context. */
@Startup
@ApplicationScoped
class StatutoryReturnDeadlineScheduler(
    private val service: StatutoryReturnService,
    private val metrics: StatutoryReturnMetricsPort,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(StatutoryReturnDeadlineScheduler::class.java)
    private lateinit var liveness: WorkflowLivenessRecorder

    @PostConstruct
    fun registerLiveness() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        every = "{openbank.statutory-returns.deadline-check-every:1h}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        val breaches = service.breaches()
        metrics.recordBreaches(breaches.size)
        liveness.recordSuccess()
        if (breaches.isNotEmpty()) {
            log.warnf(
                "%d statutory return(s) past deadline: %s",
                breaches.size,
                breaches.take(LOG_SAMPLE).joinToString {
                    "${it.returnCode}/${it.entityId}/${it.period}"
                },
            )
        }
    }

    private companion object {
        const val LOG_SAMPLE = 10
        const val WORKFLOW_NAME = "statutory-return-deadline-check"
    }
}
