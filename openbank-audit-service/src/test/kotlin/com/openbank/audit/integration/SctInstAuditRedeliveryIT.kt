// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.integration

import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The stable outbox ce-id is a retry identity, never permission to discard different evidence. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SctInstAuditRedeliveryIT {
    @Inject lateinit var repository: AuditRepository

    @Test
    fun `same SEPA event is idempotent but conflicting evidence is rejected`() {
        val eventId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        val entry = AuditEntry(
            id = eventId,
            eventType = "SctInstPaymentSubmitted",
            aggregateType = "PAYMENT",
            aggregateId = paymentId.toString(),
            actorId = null,
            actorType = null,
            payload = "{\"type\":\"SctInstPaymentSubmitted\",\"paymentId\":\"$paymentId\"}",
            sourceService = "sepa-instant",
            correlationId = null,
            occurredAt = Instant.parse("2026-10-08T00:00:00Z"),
            recordedAt = Instant.parse("2026-10-08T00:00:00Z"),
            occurredAtSource = OccurredAtSource.EVENT,
        )

        onEventLoop {
            repository.save(entry)
            repository.save(entry.copy(recordedAt = entry.recordedAt.plusSeconds(30)))
        }

        assertThatThrownBy { onEventLoop { repository.save(entry.copy(payload = "{\"conflict\":true}")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { onEventLoop { repository.save(entry.copy(eventType = "SctInstPaymentSettled")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { onEventLoop { repository.save(entry.copy(aggregateId = UUID.randomUUID().toString())) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { onEventLoop { repository.save(entry.copy(occurredAt = entry.occurredAt.plusSeconds(1))) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { onEventLoop { repository.save(entry.copy(sourceService = "other-service")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(onEventLoop { repository.findByAggregateId(paymentId.toString()) }.map { it.id })
            .containsExactly(eventId)
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
