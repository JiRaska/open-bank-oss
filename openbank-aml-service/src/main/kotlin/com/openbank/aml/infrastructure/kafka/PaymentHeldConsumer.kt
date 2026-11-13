// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.out.AccountPartyPort
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.messaging.EventRetry
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/** The debtor's party could not be established right now; worth another attempt, then the dead-letter queue. */
class HeldPaymentPartyUnresolvedException(accountId: UUID) :
    RuntimeException("No owning party could be resolved for debtor account $accountId of a held payment")

/**
 * Opens the ONE case of a held payment (ADR-0374 D3, D13).
 *
 * A payment rail writes `PAYMENT_HELD` to its outbox in the transaction that moves the payment to
 * HELD, so a hold whose case request was lost cannot exist; this consumer turns the request into a
 * case, idempotent on the case key. The rail calls no case endpoint for a hold.
 *
 * The event is rail-neutral, so this is one consumer for every rail: the channel lists the rails'
 * event topics and every other event type on them is ignored.
 *
 * The case key is DERIVED here from the rail and the payment id, and an event that claims any other
 * key is refused. The key is what a rail later accepts a decision from, and it is the namespace that
 * separates a rail's case from a confidential pack-monitoring one, so a producer does not get to
 * choose it.
 *
 * Failure rule, as for the other consumers of this service: an event that is not a held-payment
 * event is ignored; a held-payment event that cannot be read, or whose case cannot be written, is
 * rethrown so the dead-letter queue parks it. Acking it would leave a payment HELD with nobody
 * asked to decide it, until it expires.
 */
@ApplicationScoped
class PaymentHeldConsumer(
    private val cases: AmlCaseUseCase,
    private val accountParty: AccountPartyPort,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    private val log = Logger.getLogger(PaymentHeldConsumer::class.java)

    @Incoming(CHANNEL)
    suspend fun consume(payload: String) {
        val held = read(payload)
        if (held == null) {
            meters.counter(METRIC, "outcome", "ignored").increment()
            return
        }
        val case = EventRetry.withRetry(
            log,
            "held payment case intake",
            held.paymentId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) {
            cases.createCase(
                CreateAmlCaseCommand(
                    idempotencyKey = held.caseKey,
                    partyId = debtorParty(held),
                    accountId = held.debtorAccountId,
                    transactionId = held.paymentId,
                    customerReference = held.customerReference,
                    screeningType = ScreeningType.TRANSACTION_MONITORING,
                    // HIGH opens the case UNDER_REVIEW: a held payment is waiting on it.
                    riskLevel = AmlRiskLevel.HIGH,
                    alertCode = ALERT_CODE,
                    alertDetail = held.detail,
                    matchedEntity = held.matchedEntity,
                ),
            )
        }
        meters.counter(METRIC, "outcome", "case_opened").increment()
        log.infof("Held %s payment %s: case %s (%s)", held.rail, held.paymentId, case.id, case.status)
    }

    private fun read(payload: String): HeldPayment? = try {
        parse(objectMapper.readTree(payload))
    } catch (e: IllegalArgumentException) {
        meters.counter(METRIC, "outcome", "rejected").increment()
        throw e
    } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
        meters.counter(METRIC, "outcome", "rejected").increment()
        throw IllegalArgumentException("payment event is not JSON", e)
    }

    /** The party the rail named, or the account authority's answer when standing could not be read. */
    private suspend fun debtorParty(held: HeldPayment): UUID = held.debtorPartyId
        ?: accountParty.findPartyByAccountId(held.debtorAccountId)
        ?: throw HeldPaymentPartyUnresolvedException(held.debtorAccountId)

    /** A `PAYMENT_HELD` event, reduced to what a case needs. */
    data class HeldPayment(
        val paymentId: UUID,
        val rail: String,
        val caseKey: String,
        val debtorAccountId: UUID,
        val debtorPartyId: UUID?,
        val customerReference: String,
        val detail: String,
        val matchedEntity: String?,
    )

    companion object {
        const val CHANNEL = "payment-held-in"
        const val EVENT_TYPE = "PAYMENT_HELD"

        /** Alert code of a hold's case; a rail releases only on a decision of a case that carries it. */
        const val ALERT_CODE = "PAYMENT_HELD"
        const val METRIC = "openbank.aml.payment_held.events"
        private const val MAX_DETAIL = 2_000
        private const val MAX_REFERENCE = 128
        private const val MAX_MATCHED_ENTITY = 255
        private val RAIL = Regex("[A-Z][A-Z0-9_]{0,31}")

        /** The key of the one case a hold on [paymentId] of [rail] opens. */
        fun caseKey(rail: String, paymentId: UUID): String = "payment-hold:${rail.lowercase()}:$paymentId"

        /** `null` for any other event type on a rail's topic. */
        fun parse(node: JsonNode?): HeldPayment? {
            require(node != null && node.isObject) { "payment event is not a JSON object" }
            if (node.path("eventType").asText() != EVENT_TYPE) return null
            val paymentId = node.uuid("paymentId")
            val rail = node.path("rail").asText("")
            require(rail.matches(RAIL)) { "rail is not a rail code" }
            val key = caseKey(rail, paymentId)
            require(node.path("caseKey").asText() == key) { "caseKey is not this hold's case key" }
            val party = node.path("debtorPartyId")
            val reference = node.path("customerReference").asText("").trim()
            require(reference.isNotEmpty() && reference.length <= MAX_REFERENCE) { "customerReference is missing" }
            return HeldPayment(
                paymentId = paymentId,
                rail = rail,
                caseKey = key,
                debtorAccountId = node.uuid("debtorAccountId"),
                debtorPartyId = if (party.isMissingNode || party.isNull) null else node.uuid("debtorPartyId"),
                customerReference = reference,
                detail = detail(rail, node).take(MAX_DETAIL),
                matchedEntity = matchedEntity(node),
            )
        }

        private fun JsonNode.uuid(field: String): UUID {
            val value = path(field)
            require(value.isTextual) { "$field is missing" }
            return runCatching { UUID.fromString(value.textValue()) }
                .getOrElse { throw IllegalArgumentException("$field is not a UUID", it) }
        }

        /** What the reviewer reads first: why it is held, then each control that did not simply allow. */
        private fun detail(rail: String, node: JsonNode): String {
            val reasons = node.path("reasons").mapNotNull { it.asText().ifBlank { null } }
            val steps = node.path("steps").filter { it.path("outcome").asText() in setOf("DECIDED", "UNAVAILABLE") }
                .joinToString("; ") { step ->
                    val evidence = step.path("evidence").fields().asSequence()
                        .joinToString(", ") { (key, value) -> "$key=${value.asText()}" }
                    listOf(
                        step.path("step").asText(),
                        step.path("decision").asText(step.path("outcome").asText()),
                        step.path("reason").asText(""),
                        evidence,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                }
            return "$rail payment held: ${reasons.joinToString(", ").ifBlank { "no reason given" }}. " +
                "Expires ${node.path("expiresAt").asText("unknown")}. $steps"
        }

        private fun matchedEntity(node: JsonNode): String? = node.path("steps")
            .flatMap { it.path("evidence").fields().asSequence().toList() }
            .firstOrNull { (key, _) -> key.endsWith("_matched_entity") }
            ?.value?.asText()?.ifBlank { null }?.take(MAX_MATCHED_ENTITY)
    }
}
