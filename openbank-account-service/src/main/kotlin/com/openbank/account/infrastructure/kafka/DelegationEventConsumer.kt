// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.DelegatedAccessGrant
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.delay
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.OffsetDateTime
import java.util.UUID

private data class DelegationEvent(
    val type: String,
    val grantId: UUID,
    val granteePartyId: UUID,
    val resourceType: String,
    val resourceId: UUID?,
    val capabilities: Set<String>,
    val perTxLimitAmount: java.math.BigDecimal?,
    val perTxLimitCurrency: String?,
    val validFrom: OffsetDateTime?,
    val validTo: OffsetDateTime?,
)

/**
 * Maintains the local enforcement projection of delegation-service grants
 * (ADR-0232 D3) from the `openbank.delegation.events` stream.
 *
 * Only `ACCOUNT`-scoped events are projected here; savings/card/object grants are
 * the owning services' slices. OFFERED/DECLINED never create an enforceable row
 * (AC1). Failure handling mirrors PartyEventConsumer: poison pills are logged and
 * acked, transient projection failures get a bounded retry and then escape to the
 * DLQ — a swallowed failure would leave a REVOKED grant enforceable, which is the
 * worst direction this projection can drift.
 */
@ApplicationScoped
class DelegationEventConsumer(
    private val projectionRepository: DelegationProjectionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(DelegationEventConsumer::class.java)

    @Incoming("delegation-events-in")
    suspend fun consume(payload: String) {
        val event = parseEnvelope(payload)
        if (event == null) {
            log.warnf("Dropping unprocessable delegation event (poison pill): %.300s", payload)
            return
        }
        withBoundedRetry(event) { dispatch(event) }
    }

    private fun parseEnvelope(payload: String): DelegationEvent? {
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return null
        val grantId = runCatching { UUID.fromString(node.path("aggregateId").asText()) }.getOrNull() ?: return null
        val grantee = runCatching { UUID.fromString(node.path("granteePartyId").asText()) }.getOrNull() ?: return null
        val resourceId = node.path("resourceId").asText(null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val caps = node.path("capabilities").takeIf { it.isArray }
            ?.mapNotNull { it.asText(null) }?.toSet() ?: emptySet()
        return DelegationEvent(
            type = node.path("eventType").asText(""),
            grantId = grantId,
            granteePartyId = grantee,
            resourceType = node.path("resourceType").asText(""),
            resourceId = resourceId,
            capabilities = caps,
            perTxLimitAmount = node.path("perTransactionLimit").path("amount").asText(null)?.toBigDecimalOrNull(),
            perTxLimitCurrency = node.path("perTransactionLimit").path("currency").asText(null),
            validFrom = node.path("validFrom").asText(null)?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
            },
            validTo = node.path("validTo").asText(null)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
        )
    }

    private suspend fun dispatch(event: DelegationEvent) {
        if (event.resourceType != RESOURCE_ACCOUNT && event.type in ACCOUNT_SCOPED_TYPES) return
        when (event.type) {
            "DelegationActivated", "DelegationReinstated" -> upsert(event)
            "DelegationRevoked", "DelegationSuspended", "DelegationRenounced", "DelegationExpired" ->
                projectionRepository.closeById(event.grantId)
            else -> Unit // OFFERED/DECLINED/unknown: nothing enforceable to do, ack.
        }
    }

    private suspend fun upsert(event: DelegationEvent) {
        val accountId = event.resourceId ?: return
        projectionRepository.upsertActive(
            DelegatedAccessGrant(
                id = event.grantId,
                accountId = accountId,
                granteePartyId = event.granteePartyId,
                capabilities = event.capabilities,
                perTransactionLimitAmount = event.perTxLimitAmount,
                perTransactionLimitCurrency = event.perTxLimitCurrency,
                validFrom = event.validFrom ?: OffsetDateTime.now(),
                validTo = event.validTo,
                active = true,
            ),
        )
    }

    private suspend fun withBoundedRetry(event: DelegationEvent, block: suspend () -> Unit) {
        var attempt = 1
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                if (attempt >= MAX_PROJECTION_ATTEMPTS) {
                    log.errorf(
                        e,
                        "delegation event %s/%s failed after %d attempts (%s: %s) — dead-lettering",
                        event.type,
                        event.grantId,
                        attempt,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    throw e
                }
                log.warnf(
                    "delegation event %s/%s projection attempt %d/%d failed (%s: %s) — retrying",
                    event.type,
                    event.grantId,
                    attempt,
                    MAX_PROJECTION_ATTEMPTS,
                    e.javaClass.simpleName,
                    e.message,
                )
                delay(RETRY_BACKOFF_MS * attempt)
                attempt++
            }
        }
    }

    private companion object {
        const val RESOURCE_ACCOUNT = "ACCOUNT"
        const val MAX_PROJECTION_ATTEMPTS = 4
        const val RETRY_BACKOFF_MS = 500L

        val ACCOUNT_SCOPED_TYPES = setOf(
            "DelegationActivated",
            "DelegationReinstated",
            "DelegationRevoked",
            "DelegationSuspended",
            "DelegationRenounced",
            "DelegationExpired",
        )
    }
}
