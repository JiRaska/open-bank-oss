// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.tax.application.usecase.TaxFilingService
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.ObservedRemittance
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Consumes `interest.withholding.remitted.v1` for the §38d filing (ADR-0180).
 *
 * A **second consumer group** on `openbank.interest.accrual.event`, alongside interest-service's
 * own settlement consumer. No change to interest-service's contract: it already publishes this
 * event, and until now nothing consumed it for filing purposes — ADR-0038 delegated the statutory
 * filing to "the downstream payment/reporting consumer" and never named one.
 *
 * **Event-type filtering is header-based, and that is not a stylistic choice.** The outbox relay
 * carries the event type only as the `ce-type` Kafka header (ADR-0003/ADR-0050 N3); the interest
 * publisher does NOT duplicate `eventType` into the JSON payload. A consumer that filtered on a
 * payload field would compile, deploy, run, and match nothing — forever, silently, while the topic
 * carried exactly the events it was written for. Verified against the producer
 * (`WithholdingRemittanceService.remittedEvent`) and the existing self-consumer rather than assumed.
 *
 * A record with no such header is ignored: it cannot have come from the compliant outbox relay.
 *
 * **Failure handling separates two things this consumer used to conflate (#5698/#5745).**
 *
 * A **malformed event** — unparseable JSON, an unparsable `totalTaxAmount`, a missing `dueDate` — is
 * unretryable: replaying it fails identically forever, so it is counted and acked. That is the
 * genuine poison pill and the only case that may be acked on failure.
 *
 * A **failed write** ([TaxFilingService.observe] → `openIfAbsent` + `record`) is the opposite: the
 * event is fine, the database is not. Acking there was the worst variant of this bug class in the
 * fleet, because nothing downstream can tell. `assemble` totals only what was *observed*, so a lost
 * remittance does not surface as an error or a gap — it silently **understates the tax withheld on a
 * §38d return that then gets filed**, and the correction is a `dodatečné vyúčtování` after the fact.
 * `auto.offset.reset: latest` also rules out recovering it by replay. Those failures now go through
 * [EventRetry.withRetry] and are RETHROWN.
 *
 * **This channel HALTS on a persistent failure, and that is currently unavoidable — say so rather
 * than call it a dead-letter.** The mechanism this handler controls is the rethrow: the record is not
 * acknowledged as done. What follows is the connector's `failure-strategy` for
 * `withholding-remitted-in`, and unlike its siblings this one cannot yet be given a DLQ. #5751 wires
 * the fleet's incoming channels to explicit dead-letter topics and deliberately BASELINES this one:
 * `openbank-tax-reporting-service` has no `KafkaUser` manifest anywhere under
 * `openbank-infra/gitops/components/`, so no `Write` ACL can be granted for a dead-letter topic, and
 * a DLQ configured without the ACL wedges on the DLQ send itself — parking the record on the very
 * failure it was meant to park. So SmallRye's default `fail` applies and the channel stops.
 *
 * That is a real operational property of a statutory filing path, not a footnote: a §38d remittance
 * arriving during a tax-db outage stops this consumer group until someone intervenes. It is still the
 * right trade against the alternative — a halted channel is loud and its backlog is intact, whereas
 * the old ack silently understated a return that then got filed — but it is a trade someone must
 * know about while operating this service. Creating a KafkaUser for tax-reporting is what unblocks
 * the DLQ; until then, this consumer wedging IS the alert.
 *
 * `observe` is idempotent on the remittance id (that is what the `duplicate` outcome is), so a retry
 * or a redelivery cannot double-count a batch into the return.
 */
@ApplicationScoped
class WithholdingRemittedConsumer(
    private val taxFilingService: TaxFilingService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(WithholdingRemittedConsumer::class.java)

    @Incoming("withholding-remitted-in")
    @Suppress("TooGenericExceptionCaught") // the two catches below mean opposite things; see the KDoc
    suspend fun consume(record: ConsumerRecord<String, String>) {
        val eventType = record.headers().lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.let { String(it.value(), StandardCharsets.UTF_8) }
        if (eventType != EVENT_WITHHOLDING_REMITTED) {
            count(OUTCOME_IGNORED)
            return
        }

        // Poison pill: an event this consumer cannot decode fails identically on every replay, so it
        // is counted and acked rather than wedging the group and stalling every later filing period.
        val remittance = try {
            parse(objectMapper.readTree(record.value()))
        } catch (e: Exception) {
            count(OUTCOME_MALFORMED)
            log.errorf(e, "[withholding-filing] unparseable remitted event, acking: %.300s", record.value())
            return
        }

        try {
            val recorded = EventRetry.withRetry(log, "§38d remittance observation", remittance.remittanceId) {
                taxFilingService.observe(remittance)
            }
            count(if (recorded) OUTCOME_RECORDED else OUTCOME_DUPLICATE)
        } catch (e: Exception) {
            // Counted BEFORE the rethrow so the `failed` population survives whichever
            // failure-strategy the channel is configured with — today, a halt (see the KDoc).
            count(OUTCOME_FAILED)
            throw e
        }
    }

    private fun parse(node: JsonNode) = ObservedRemittance(
        remittanceId = UUID.fromString(node.path("remittanceId").asText()),
        period = FilingPeriod(node.path("periodYear").asInt(), node.path("periodMonth").asInt()),
        currency = node.path("currency").asText(),
        totalTaxAmount = decimalOf(node.path("totalTaxAmount")),
        itemCount = node.path("itemCount").asInt(),
        dueDate = LocalDate.parse(node.path("dueDate").asText()),
        observedAt = Instant.now(clock),
    )

    /**
     * Strict decode: the amount is serialised as a JSON *string* by the producer, and a silent
     * fallback to zero on an unparsable value would file a return understating the tax withheld.
     */
    private fun decimalOf(node: JsonNode): BigDecimal {
        val raw = if (node.isTextual) node.asText() else node.toString()
        return runCatching { BigDecimal(raw) }.getOrElse {
            throw IllegalArgumentException("Unparsable totalTaxAmount in remitted event: $raw")
        }
    }

    private fun count(outcome: String) {
        meterRegistry.counter("openbank.tax.withholding_events", "outcome", outcome).increment()
    }

    companion object {
        private const val EVENT_WITHHOLDING_REMITTED = "interest.withholding.remitted.v1"

        private const val OUTCOME_RECORDED = "recorded"
        private const val OUTCOME_DUPLICATE = "duplicate"
        private const val OUTCOME_IGNORED = "ignored_event_type"
        private const val OUTCOME_MALFORMED = "malformed"
        private const val OUTCOME_FAILED = "failed"
    }
}
