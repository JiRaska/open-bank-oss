// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.domestic.application.port.`in`.ApplyDelegatedSpendReservationStateUseCase
import com.openbank.domestic.application.port.out.ReservationProjectionApplyResult
import com.openbank.libs.messaging.SyntheticTaintKafkaRail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Function
import java.util.function.Supplier

/**
 * The handler takes `Message<String>` (ADR-0252 #8630), so acknowledgement is now its own job and
 * a malformed record NACKS instead of throwing. These assertions moved with it: asserting the
 * throw would keep passing against a handler that swallowed the record and acked it, which is the
 * outcome that loses the event silently.
 */
class DelegatedSpendReservationStateConsumerTest {
    private val useCase = mockk<ApplyDelegatedSpendReservationStateUseCase>()
    private val consumer = DelegatedSpendReservationStateConsumer(ObjectMapper().registerKotlinModule(), useCase)

    private class Settlement {
        var acked = false
        var nackCause: Throwable? = null
    }

    private fun message(payload: String, settlement: Settlement): Message<String> = Message.of(
        payload,
        Supplier<CompletionStage<Void>> {
            settlement.acked = true
            CompletableFuture.completedFuture(null)
        },
        Function<Throwable, CompletionStage<Void>> { cause ->
            settlement.nackCause = cause
            CompletableFuture.completedFuture(null)
        },
    )

    @Test
    fun `terminal revision applies even when producer clocks make its times appear earlier`(): Unit = runBlocking {
        coEvery { useCase.apply(any()) } returns ReservationProjectionApplyResult.APPLIED

        val settlement = Settlement()
        consumer.consume(
            message(
                payload(
                    state = "RELEASED",
                    reservationVersion = 2,
                    createdAt = "2026-09-01T12:00:01Z",
                    settledAt = "2026-09-01T12:00:00Z",
                    occurredAt = "2026-09-01T12:00:00Z",
                ),
                settlement,
            ),
        )

        assertThat(settlement.acked).isTrue()
        assertThat(settlement.nackCause).isNull()

        coVerify(exactly = 1) {
            useCase.apply(
                withArg { snapshot ->
                    assertThat(snapshot.reservationVersion).isEqualTo(2)
                    assertThat(checkNotNull(snapshot.settledAt)).isBefore(snapshot.createdAt)
                    assertThat(snapshot.idempotencyKeyHash).isEqualTo(
                        "d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad",
                    )
                },
            )
        }
    }

    @Test
    fun `producer offset and legacy nanoseconds are canonicalized to microsecond instants`(): Unit = runBlocking {
        coEvery { useCase.apply(any()) } returns ReservationProjectionApplyResult.APPLIED

        consumer.consume(
            message(
                payload(
                    state = "RELEASED",
                    reservationVersion = 2,
                    createdAt = "2026-09-01T10:15:30.123456789+02:00",
                    settledAt = "2026-09-01T10:17:30.123456789+02:00",
                    occurredAt = "2026-09-01T08:17:30.123456789Z",
                ),
                Settlement(),
            ),
        )

        coVerify(exactly = 1) {
            useCase.apply(
                withArg { snapshot ->
                    assertThat(snapshot.createdAt).isEqualTo(Instant.parse("2026-09-01T08:15:30.123456Z"))
                    assertThat(snapshot.settledAt).isEqualTo(Instant.parse("2026-09-01T08:17:30.123456Z"))
                    assertThat(snapshot.occurredAt).isEqualTo(Instant.parse("2026-09-01T08:17:30.123456Z"))
                },
            )
        }
    }

    @Test
    fun `raw idempotency key cannot substitute for the privacy-preserving hash`() {
        val rawOnly = payload().replace(
            "\"idempotencyKeyHash\":\"d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad\"",
            "\"idempotencyKey\":\"payment-42\"",
        )

        val settlement = Settlement()
        runBlocking { consumer.consume(message(rawOnly, settlement)) }

        assertThat(settlement.acked).isFalse()
        assertThat(settlement.nackCause)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("idempotencyKeyHash is required text")
        coVerify(exactly = 0) { useCase.apply(any()) }
    }

    @Test
    fun `aggregate id must equal reservation id`() {
        val differentAggregate = payload().replace(
            "\"aggregateId\":\"$RESERVATION_ID\"",
            "\"aggregateId\":\"${UUID.randomUUID()}\"",
        )

        val settlement = Settlement()
        runBlocking { consumer.consume(message(differentAggregate, settlement)) }

        assertThat(settlement.acked).isFalse()
        assertThat(settlement.nackCause)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("aggregateId must equal reservationId")
        coVerify(exactly = 0) { useCase.apply(any()) }
    }

    @Test
    fun `a record with no Kafka metadata is REAL, and the rail is torn down afterwards`(): Unit = runBlocking {
        var taintedDuringApply: Boolean? = null
        coEvery { useCase.apply(any()) } answers {
            taintedDuringApply = SyntheticTaintKafkaRail.currentlyTainted()
            ReservationProjectionApplyResult.APPLIED
        }

        consumer.consume(message(payload(), Settlement()))

        // No IncomingKafkaRecordMetadata means no header, which means no claim, which means REAL.
        assertThat(taintedDuringApply).isFalse()
        assertThat(SyntheticTaintKafkaRail.currentlyTainted()).isFalse()
    }

    @Suppress("LongParameterList")
    private fun payload(
        state: String = "RESERVED",
        reservationVersion: Long = 1,
        createdAt: String = "2026-09-01T12:00:00Z",
        settledAt: String? = null,
        occurredAt: String = createdAt,
    ): String =
        """
        {
          "eventId":"30000000-0000-4000-8000-000000000001",
          "aggregateId":"$RESERVATION_ID",
          "aggregateType":"DelegationSpendReservation",
          "eventType":"DelegationSpendReservationStateChanged",
          "version":1,
          "occurredAt":"$occurredAt",
          "sourceService":"delegation-service",
          "reservationId":"$RESERVATION_ID",
          "delegationId":"30000000-0000-4000-8000-000000000003",
          "grantorPartyId":"30000000-0000-4000-8000-000000000004",
          "granteePartyId":"30000000-0000-4000-8000-000000000005",
          "resourceType":"ACCOUNT",
          "resourceId":"30000000-0000-4000-8000-000000000006",
          "amount":1500.00,
          "currency":"CZK",
          "idempotencyKeyHash":"d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad",
          "operationType":"DOMESTIC_PAYMENT",
          "state":"$state",
          "reservationVersion":$reservationVersion,
          "createdAt":"$createdAt",
          "settledAt":${settledAt?.let { "\"$it\"" } ?: "null"}
        }
        """.trimIndent()

    private companion object {
        val RESERVATION_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000002")
    }
}
