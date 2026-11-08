// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.tax.application.usecase.CeuObservation
import com.openbank.tax.application.usecase.CeuRegisterService
import com.openbank.tax.domain.ceu.CeuRole
import com.openbank.tax.domain.ceu.RegisterFact
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Feeds the CEÚ register projection (ADR-0367 D7, #15292) from two streams:
 *
 *  - `openbank.accounts.account.created` — AccountCreated (account + its HOLDER), AccountClosed,
 *    and AccountStatusChanged to CLOSED;
 *  - `openbank.delegation.events` — DelegationActivated on an ACCOUNT resource starts a DISPOSER
 *    tie; Revoked / Expired / Renounced / Suspended end it, Reinstated restarts it.
 *
 * The ADR-0252 taint is read from the Kafka HEADER, never the payload, and only an exact `true`
 * counts (fail-to-real, [SyntheticTaint.isTainted]). Tainted facts are still RECORDED — dropping
 * them here would make the per-filing dropped count impossible to evidence; the fold excludes them.
 *
 * Beneficial owners have no event source on `main` (party-service publishes no UBO event). The
 * role exists in the model; until a source exists the projection carries no BENEFICIAL_OWNER
 * ties, and the spec says so rather than reporting an empty set as if it were complete.
 *
 * Failure handling matches the withholding consumer (#5698/#5745): an undecodable target event is
 * nacked to the channel's DLQ, never acked; a failed write is retried and rethrown. Neither the
 * logs nor the exception carry the payload.
 */
@ApplicationScoped
class CeuRegisterEventConsumer(
    private val service: CeuRegisterService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(CeuRegisterEventConsumer::class.java)

    @Incoming("ceu-account-events-in")
    suspend fun consumeAccount(record: ConsumerRecord<String, String>) {
        val type = eventType(record)
        if (type !in ACCOUNT_TYPES) return count(STREAM_ACCOUNT, OUTCOME_IGNORED)
        val node = decode(record, STREAM_ACCOUNT)
        if (type == "AccountStatusChanged" && node.path("newStatus").asText() != "CLOSED") {
            return count(STREAM_ACCOUNT, OUTCOME_IGNORED)
        }
        val facts = try {
            accountFacts(
                type,
                node,
                eventId(record),
                SyntheticTaint.isTainted(header(record, OutboxKafkaHeaders.HEADER_SYNTHETIC)),
            )
        } catch (_: RuntimeException) {
            malformed(STREAM_ACCOUNT)
        }
        facts.forEach { fact -> store(STREAM_ACCOUNT, fact.eventId) { service.record(fact) } }
    }

    @Incoming("ceu-delegation-events-in")
    suspend fun consumeDelegation(record: ConsumerRecord<String, String>) {
        val type = eventType(record)
        if (type !in DELEGATION_START_TYPES && type !in DELEGATION_END_TYPES) {
            return count(STREAM_DELEGATION, OUTCOME_IGNORED)
        }
        val node = decode(record, STREAM_DELEGATION)
        val eventId = eventId(record)
        val synthetic = SyntheticTaint.isTainted(header(record, OutboxKafkaHeaders.HEADER_SYNTHETIC))
        val now = Instant.now(clock)
        try {
            val grantId = UUID.fromString(node.path("aggregateId").asText())
            if (type in DELEGATION_START_TYPES) {
                if (type == "DelegationReinstated") {
                    // Reinstated carries no resource; it restarts a tie already placed by its activation.
                    val on = occurredOn(node)
                    return store(STREAM_DELEGATION, eventId) {
                        service.reinstateRelation(eventId, grantId, on, now, synthetic)
                    }
                }
                if (node.path("resourceType").asText() != "ACCOUNT") {
                    return count(STREAM_DELEGATION, OUTCOME_IGNORED)
                }
                val fact = RegisterFact.RelationStarted(
                    eventId = eventId,
                    relationId = grantId,
                    accountId = UUID.fromString(node.path("resourceId").asText()),
                    partyId = UUID.fromString(node.path("granteePartyId").asText()),
                    role = CeuRole.DISPOSER,
                    effectiveOn = node.path("validFrom").asText(null)?.let { OffsetDateTime.parse(it).toInstant() }
                        ?.let(::localDate) ?: occurredOn(node),
                    recordedAt = now,
                    synthetic = synthetic,
                )
                store(STREAM_DELEGATION, eventId) { service.record(fact) }
            } else {
                val on = occurredOn(node)
                store(STREAM_DELEGATION, eventId) { service.endRelation(eventId, grantId, on, now, synthetic) }
            }
        } catch (e: IllegalArgumentException) {
            if (e.message == MALFORMED) throw e
            malformed(STREAM_DELEGATION)
        } catch (_: java.time.DateTimeException) {
            malformed(STREAM_DELEGATION)
        }
    }

    private fun accountFacts(type: String, node: JsonNode, eventId: UUID, synthetic: Boolean): List<RegisterFact> {
        val accountId = UUID.fromString(node.path("aggregateId").asText())
        val on = occurredOn(node)
        val now = Instant.now(clock)
        return when (type) {
            "AccountCreated" -> listOf(
                RegisterFact.AccountOpened(
                    eventId,
                    accountId,
                    node.path("accountNumber").asText(),
                    node.path("currency").asText(),
                    on,
                    now,
                    synthetic,
                ),
                // The holder tie is derived from the same event, so its id is derived too: stable
                // across redelivery, distinct from the opening fact's id.
                RegisterFact.RelationStarted(
                    UUID.nameUUIDFromBytes("$eventId:holder".toByteArray()),
                    holderRelation(accountId),
                    accountId,
                    UUID.fromString(node.path("partyId").asText()),
                    CeuRole.HOLDER,
                    on,
                    now,
                    synthetic,
                ),
            )
            else -> listOf(RegisterFact.AccountClosed(eventId, accountId, on, now, synthetic))
        }
    }

    @Suppress("TooGenericExceptionCaught") // a failed write is counted, then rethrown to the DLQ
    private suspend fun store(stream: String, key: UUID, write: suspend () -> CeuObservation) {
        try {
            val outcome = EventRetry.withRetry(log, "CEÚ register $stream", key) { write() }
            count(stream, outcome.name.lowercase())
        } catch (e: Exception) {
            count(stream, OUTCOME_FAILED)
            throw e
        }
    }

    private fun decode(record: ConsumerRecord<String, String>, stream: String): JsonNode =
        runCatching { objectMapper.readTree(record.value()) }.getOrNull() ?: malformed(stream)

    private fun malformed(stream: String): Nothing {
        count(stream, OUTCOME_MALFORMED)
        log.errorf("[ceu] malformed %s event; sending original record to DLQ", stream)
        throw IllegalArgumentException(MALFORMED)
    }

    /** `ce-type` header first (the outbox relay's carrier), the payload's `eventType` second. */
    private fun eventType(record: ConsumerRecord<String, String>): String =
        header(record, OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?: runCatching { objectMapper.readTree(record.value()).path("eventType").asText(null) }.getOrNull()
            ?: ""

    /** `ce-id` when present; otherwise a hash of the record bytes, which is stable across redelivery. */
    private fun eventId(record: ConsumerRecord<String, String>): UUID =
        header(record, OutboxKafkaHeaders.HEADER_EVENT_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.nameUUIDFromBytes((record.value() ?: "").toByteArray(StandardCharsets.UTF_8))

    private fun occurredOn(node: JsonNode): LocalDate = localDate(Instant.parse(node.path("occurredAt").asText()))

    private fun count(stream: String, outcome: String) {
        meterRegistry.counter("openbank.tax.ceu_register_events", "stream", stream, "outcome", outcome).increment()
    }

    companion object {
        private fun header(record: ConsumerRecord<String, String>, name: String): String? =
            record.headers().lastHeader(name)?.let { String(it.value(), StandardCharsets.UTF_8) }

        private fun localDate(at: Instant): LocalDate = at.atZone(PRAGUE).toLocalDate()

        /** CEÚ business dates are Czech dates; an event at 23:30 UTC in winter is the NEXT day in Prague. */
        val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

        fun holderRelation(accountId: UUID): UUID = UUID.nameUUIDFromBytes("holder:$accountId".toByteArray())

        private const val MALFORMED = "Malformed CEÚ register event"
        private val ACCOUNT_TYPES = setOf("AccountCreated", "AccountClosed", "AccountStatusChanged")
        private val DELEGATION_START_TYPES = setOf("DelegationActivated", "DelegationReinstated")
        private val DELEGATION_END_TYPES =
            setOf("DelegationRevoked", "DelegationExpired", "DelegationRenounced", "DelegationSuspended")
        private const val STREAM_ACCOUNT = "account"
        private const val STREAM_DELEGATION = "delegation"
        private const val OUTCOME_IGNORED = "ignored"
        private const val OUTCOME_MALFORMED = "malformed"
        private const val OUTCOME_FAILED = "failed"
    }
}
