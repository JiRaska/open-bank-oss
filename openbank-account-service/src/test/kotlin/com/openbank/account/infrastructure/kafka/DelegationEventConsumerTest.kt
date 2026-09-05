// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.application.port.out.DelegationProjectionRepository
import com.openbank.account.domain.model.DelegatedAccessGrant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DelegationEventConsumerTest {

    private val repository: DelegationProjectionRepository = mockk(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var consumer: DelegationEventConsumer

    private val grantId: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()
    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        consumer = DelegationEventConsumer(repository, objectMapper)
    }

    private fun event(
        type: String,
        resourceType: String = "ACCOUNT",
        resourceId: UUID? = accountId,
        revision: Long? = 1,
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "eventType" to type,
            "aggregateId" to grantId,
            "grantorPartyId" to grantor,
            "granteePartyId" to grantee,
            "resourceType" to resourceType,
            "resourceId" to resourceId,
            "capabilities" to listOf("ACCOUNT_READ_BALANCES", "ACCOUNT_INITIATE_PAYMENT"),
            "perTransactionLimit" to mapOf("amount" to "5000.00", "currency" to "CZK"),
            "validFrom" to "2026-07-31T12:00:00Z",
            "validTo" to "2027-07-31T12:00:00Z",
            "occurredAt" to "2026-08-01T12:00:00Z",
            "lifecycleRevision" to revision,
        ),
    )

    @Test
    fun `DelegationActivated upserts an active projection row`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated"))
        coVerify {
            repository.applyActive(
                match<DelegatedAccessGrant> {
                    it.id == grantId &&
                        it.accountId == accountId &&
                        // Without the grantor the guard cannot ask whether the issuer owns the
                        // account, and a projection row becomes authority in itself.
                        it.grantorPartyId == grantor &&
                        it.active &&
                        "ACCOUNT_READ_BALANCES" in it.capabilities &&
                        it.perTransactionLimitAmount?.compareTo("5000.00".toBigDecimal()) == 0
                },
                1,
            )
        }
    }

    @Test
    fun `an event with no readable grantor is a poison pill, not an optimistic row`(): Unit = runBlocking {
        val noGrantor = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "DelegationActivated",
                "aggregateId" to grantId,
                "granteePartyId" to grantee,
                "resourceType" to "ACCOUNT",
                "resourceId" to accountId,
                "capabilities" to listOf("ACCOUNT_INITIATE_PAYMENT"),
                "occurredAt" to "2026-08-01T12:00:00Z",
            ),
        )

        consumer.consume(noGrantor)

        // Projecting it would create a row the ownership check can never evaluate.
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    @Test
    fun `close events mark the row inactive`(): Unit = runBlocking {
        for (type in listOf("DelegationRevoked", "DelegationSuspended", "DelegationRenounced", "DelegationExpired")) {
            consumer.consume(event(type))
        }
        coVerify(exactly = 4) { repository.applyClosed(grantId, 1) }
    }

    @Test
    fun `OFFERED and DECLINED never create an enforceable row`(): Unit = runBlocking {
        consumer.consume(event("DelegationOffered"))
        consumer.consume(event("DelegationDeclined"))
        coVerify(exactly = 0) { repository.upsertActive(any()) }
        coVerify(exactly = 0) { repository.closeById(any()) }
    }

    @Test
    fun `SAVINGS_GOAL events are projected with their resource type`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated", resourceType = "SAVINGS_GOAL"))
        coVerify {
            repository.applyActive(
                match<DelegatedAccessGrant> {
                    it.id == grantId && it.resourceType == "SAVINGS_GOAL" && it.active
                },
                1,
            )
        }
    }

    @Test
    fun `non-projected lifecycle events are ignored`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated", resourceType = "CARD"))
        consumer.consume(event("DelegationActivated", resourceType = "DOCUMENT"))
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    @Test
    fun `unknown event type parses and is acked without projection work`(): Unit = runBlocking {
        consumer.consume(event("DelegationSomethingNew"))
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    @Test
    fun `poison pill is acked, never retried`(): Unit = runBlocking {
        consumer.consume("not json at all")
        consumer.consume("""{"eventType":"DelegationActivated"}""")
        coVerify(exactly = 0) { repository.upsertActive(any()) }
    }

    @Test
    fun `transient failure is retried then escapes to the DLQ`(): Unit = runBlocking {
        coEvery { repository.applyActive(any(), any()) } throws IllegalStateException("db blip")
        assertThatThrownBy { runBlocking { consumer.consume(event("DelegationActivated")) } }
            .isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 4) { repository.applyActive(any(), 1) }
    }

    @Test
    fun `revisionless opening is ignored but revisionless close installs a tombstone`(): Unit = runBlocking {
        consumer.consume(event("DelegationActivated", revision = null))
        consumer.consume(event("DelegationRevoked", revision = null))

        coVerify(exactly = 0) { repository.applyActive(any(), any()) }
        coVerify(exactly = 1) { repository.applyClosed(grantId, null) }
    }
}
