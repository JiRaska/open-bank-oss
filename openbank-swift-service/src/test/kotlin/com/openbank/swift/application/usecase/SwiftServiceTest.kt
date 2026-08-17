// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.`in`.SendSwiftCommand
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.application.port.out.SettlementOutcome
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SwiftServiceTest {

    private val repo = mockk<SwiftRepository>()
    private val schemeGatewayPort = mockk<SchemeGatewayPort>()
    private val settlementPort = mockk<SettlementPort>()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }
    private val clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val service = SwiftService(
        repo,
        schemeGatewayPort,
        settlementPort,
        objectMapper,
        clock,
        schemeSubmissionEnabled = false,
    )
    private val serviceWithFlag = SwiftService(
        repo,
        schemeGatewayPort,
        settlementPort,
        objectMapper,
        clock,
        schemeSubmissionEnabled = true,
    )

    @Test
    fun `send is idempotent`(): Unit = runBlocking {
        val existing =
            message(id = UUID.fromString("00000000-0000-0000-0000-000000000010"), status = SwiftStatus.VALIDATED)
        coEvery { repo.findByIdempotencyKey("idem-1") } returns existing

        val result = service.send(command())

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `send validates and throws IllegalArgumentException on invalid BIC`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null

        assertThatThrownBy { runBlocking { service.send(command(senderBic = "ABC123")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("SWIFT validation failed")
            .hasMessageContaining("Invalid sender BIC: ABC123")

        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `send rejects YYYY-MM-DD valueDate format with IllegalArgumentException`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null

        assertThatThrownBy { runBlocking { service.send(command(valueDate = "2026-05-27")) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("SWIFT validation failed")
            .hasMessageContaining("Invalid valueDate '2026-05-27': must be YYYYMMDD")

        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `acknowledge transitions to ACKNOWLEDGED`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val existing = message(id = id, status = SwiftStatus.SENT)
        coEvery { repo.findById(id) } returns existing
        coEvery { repo.save(any()) } answers { firstArg() }

        val result = service.acknowledge(id, "ACK-1")

        assertThat(result.status).isEqualTo(SwiftStatus.ACKNOWLEDGED)
        assertThat(result.ackReceivedAt).isNotNull
        coVerify(exactly = 1) { repo.save(match { it.status == SwiftStatus.ACKNOWLEDGED }) }
    }

    @Test
    fun `reject sets REJECTED status and rejectionReason`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000012")
        val existing = message(id = id, status = SwiftStatus.SENT)
        coEvery { repo.findById(id) } returns existing
        coEvery { repo.save(any()) } answers { firstArg() }

        val result = service.reject(id, "invalid details")

        assertThat(result.status).isEqualTo(SwiftStatus.REJECTED)
        assertThat(result.rejectionReason).isEqualTo("invalid details")
        coVerify(exactly = 1) {
            repo.save(
                match {
                    it.status == SwiftStatus.REJECTED &&
                        it.rejectionReason == "invalid details"
                },
            )
        }
    }

    @Test
    fun `send persists a new VALIDATED message when no idempotent match exists`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null
        coEvery { repo.save(any()) } answers { firstArg() }

        val result = service.send(command())

        assertThat(result.status).isEqualTo(SwiftStatus.VALIDATED)
        coVerify(exactly = 1) { repo.save(match { it.status == SwiftStatus.VALIDATED }) }
    }

    @Test
    fun `getById delegates to the repository`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val existing = message(id = id, status = SwiftStatus.VALIDATED)
        coEvery { repo.findById(id) } returns existing

        assertThat(service.getById(id)).isEqualTo(existing)
    }

    @Test
    fun `listAll delegates to the repository`(): Unit = runBlocking {
        val all = listOf(message(id = UUID.randomUUID(), status = SwiftStatus.SENT))
        coEvery { repo.listAllMessages() } returns all

        assertThat(service.listAll()).isEqualTo(all)
    }

    @Test
    fun `listByStatus delegates to the repository`(): Unit = runBlocking {
        val validated = listOf(message(id = UUID.randomUUID(), status = SwiftStatus.VALIDATED))
        coEvery { repo.findByStatus(SwiftStatus.VALIDATED) } returns validated

        assertThat(service.listByStatus(SwiftStatus.VALIDATED)).isEqualTo(validated)
    }

    @Test
    fun `acknowledge throws when the message does not exist`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000099")
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy { runBlocking { service.acknowledge(id, "ACK-1") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SWIFT message not found: $id")

        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `reject throws when the message does not exist`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000098")
        coEvery { repo.findById(id) } returns null

        assertThatThrownBy { runBlocking { service.reject(id, "bad") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SWIFT message not found: $id")

        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `send with flag on advances MT103 to COMPLETED after ACSC and settlement (ADR-0108)`(): Unit = runBlocking {
        val txId = UUID.randomUUID()
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null
        coEvery { repo.save(match { it.status == SwiftStatus.VALIDATED }) } answers { firstArg() }
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null, rawMt = "<pacs.008/>")
        coEvery { repo.saveWithOutbox(match { it.status == SwiftStatus.SENT }, any()) } answers { firstArg() }
        coEvery { settlementPort.settle(any()) } returns SettlementOutcome(settled = true, transactionId = txId)
        coEvery { repo.saveWithOutbox(match { it.status == SwiftStatus.COMPLETED }, any()) } answers { firstArg() }

        val result = serviceWithFlag.send(command())

        assertThat(result.status).isEqualTo(SwiftStatus.COMPLETED)
        coVerify(exactly = 1) { schemeGatewayPort.submit(any()) }
        coVerify(exactly = 1) { settlementPort.settle(any()) }
        coVerify(exactly = 1) { repo.saveWithOutbox(match { it.status == SwiftStatus.COMPLETED }, any()) }
    }

    @Test
    fun `send with flag on writes sourceService onto both outbox payloads for AuditConsumer attribution`(): Unit =
        runBlocking {
            // #3994/#5256: `sourceService` is the strongest (EVENT-sourced) attribution
            // `AuditConsumer` reads. Before this field, both the SENT and COMPLETED outbox
            // writes fell back to `EventAttribution.TopicAttribution`'s
            // `openbank.payments.swift.event` -> `swift-service` entry — correct, but only
            // TOPIC-sourced. Audit-service subscribes to that topic today, so this is a live
            // attribution upgrade for every SWIFT payment leg, not a forward-looking one.
            val txId = UUID.randomUUID()
            val sentOutbox = slot<OutboxMessage>()
            val completedOutbox = slot<OutboxMessage>()
            coEvery { repo.findByIdempotencyKey("idem-1") } returns null
            coEvery { repo.save(match { it.status == SwiftStatus.VALIDATED }) } answers { firstArg() }
            coEvery { schemeGatewayPort.submit(any()) } returns
                SchemeSubmissionOutcome(accepted = true, reasonCode = null, rawMt = "<pacs.008/>")
            coEvery {
                repo.saveWithOutbox(match { it.status == SwiftStatus.SENT }, capture(sentOutbox))
            } answers { firstArg() }
            coEvery { settlementPort.settle(any()) } returns SettlementOutcome(settled = true, transactionId = txId)
            coEvery {
                repo.saveWithOutbox(match { it.status == SwiftStatus.COMPLETED }, capture(completedOutbox))
            } answers { firstArg() }

            serviceWithFlag.send(command())

            val sentNode = objectMapper.readTree(sentOutbox.captured.payload)
            val completedNode = objectMapper.readTree(completedOutbox.captured.payload)
            assertThat(sentNode.get("sourceService").asText()).isEqualTo("swift-service")
            assertThat(completedNode.get("sourceService").asText()).isEqualTo("swift-service")
        }

    @Test
    fun `send with flag on holds MT103 in SENT when transaction-service is unavailable`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null
        coEvery { repo.save(match { it.status == SwiftStatus.VALIDATED }) } answers { firstArg() }
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null, rawMt = "<pacs.008/>")
        coEvery { repo.saveWithOutbox(match { it.status == SwiftStatus.SENT }, any()) } answers { firstArg() }
        coEvery { settlementPort.settle(any()) } throws SettlementUnavailableException("txn-svc down")

        val result = serviceWithFlag.send(command())

        assertThat(result.status).isEqualTo(SwiftStatus.SENT)
        coVerify(exactly = 0) { repo.saveWithOutbox(match { it.status == SwiftStatus.COMPLETED }, any()) }
    }

    @Test
    fun `send with flag on rejects MT103 with mapped reason on scheme reject (RJCT)`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null
        coEvery { repo.save(match { it.status == SwiftStatus.VALIDATED }) } answers { firstArg() }
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04", rawMt = "<pacs.002/>")
        coEvery { repo.saveWithOutbox(match { it.status == SwiftStatus.REJECTED }, any()) } answers { firstArg() }

        val result = serviceWithFlag.send(command())

        assertThat(result.status).isEqualTo(SwiftStatus.REJECTED)
        assertThat(result.rejectionReason).contains("AC04")
        coVerify(exactly = 1) { repo.saveWithOutbox(match { it.status == SwiftStatus.REJECTED }, any()) }
        coVerify(exactly = 0) { settlementPort.settle(any()) }
    }

    @Test
    fun `send with flag on holds MT103 in VALIDATED when gateway is unavailable (fail-closed)`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null
        coEvery { repo.save(match { it.status == SwiftStatus.VALIDATED }) } answers { firstArg() }
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(RuntimeException("gateway down"))

        val result = serviceWithFlag.send(command())

        assertThat(result.status).isEqualTo(SwiftStatus.VALIDATED)
        coVerify(exactly = 0) {
            repo.saveWithOutbox(
                match { it.status == SwiftStatus.SENT || it.status == SwiftStatus.REJECTED },
                any(),
            )
        }
    }

    @Test
    fun `send with flag on is a no-op for non-MT103 message types`(): Unit = runBlocking {
        coEvery { repo.findByIdempotencyKey("idem-1") } returns null
        coEvery { repo.save(any()) } answers { firstArg() }

        val result = serviceWithFlag.send(command(messageType = SwiftMessageType.MT202))

        assertThat(result.status).isEqualTo(SwiftStatus.VALIDATED)
        coVerify(exactly = 0) { schemeGatewayPort.submit(any()) }
        coVerify(exactly = 0) { settlementPort.settle(any()) }
    }

    private fun command(
        senderBic: String = "ABCDEFGH",
        receiverBic: String = "IJKLMNOP",
        messageType: SwiftMessageType = SwiftMessageType.MT103,
        valueDate: String = "20260527",
    ) = SendSwiftCommand(
        idempotencyKey = "idem-1",
        messageType = messageType,
        senderBic = senderBic,
        receiverBic = receiverBic,
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = valueDate,
        currency = "EUR",
        amountMinorUnits = 1000,
        orderingCustomerAccount = "DE89370400440532013000",
        orderingCustomerAccountId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1",
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
    )

    private fun message(id: UUID, status: SwiftStatus) = SwiftMessage(
        id = id,
        idempotencyKey = "idem-1",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = "20260527",
        currency = "EUR",
        amountMinorUnits = 1000,
        orderingCustomerAccount = "DE89370400440532013000",
        orderingCustomerAccountId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1",
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
        status = status,
        rawMt = null,
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = Instant.parse("2026-05-27T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-27T00:00:00Z"),
    )
}
