// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.application.port.out.InstitutionTransferRejection
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.InstitutionTransferSubmission
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.NonConformingMessageException
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.application.usecase.InstitutionTransferRefusedException
import com.openbank.swift.application.usecase.InstitutionTransferService
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.InstitutionTransferStatus
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.institution.InstitutionTransferFixtures.parsed
import com.openbank.swift.institution.InstitutionTransferFixtures.release
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class InstitutionTransferServiceTest {

    /** In-memory repository with the real "one rejection per payment id" rule. */
    private class FakeRepo : InstitutionTransferRepository {
        val rows = linkedMapOf<UUID, InstitutionTransfer>()
        val outbox = mutableListOf<SwiftOutboxMessage>()
        val rejections = linkedMapOf<UUID, InstitutionTransferRejection>()

        override suspend fun findByIdempotencyKey(key: String) = rows.values.firstOrNull { it.idempotencyKey == key }
        override suspend fun findByPaymentId(paymentId: UUID) = rows.values.firstOrNull { it.paymentId == paymentId }
        override suspend fun saveWithOutbox(
            transfer: InstitutionTransfer,
            outbox: List<SwiftOutboxMessage>,
        ): InstitutionTransfer {
            rows[transfer.id] = transfer
            this.outbox += outbox
            return transfer
        }
        override suspend fun recordRejectionOnce(
            rejection: InstitutionTransferRejection,
            outbox: SwiftOutboxMessage,
        ): Boolean {
            if (rejections.containsKey(rejection.paymentId)) return false
            rejections[rejection.paymentId] = rejection
            this.outbox += outbox
            return true
        }
    }

    private class Gateway(var next: () -> InstitutionTransferSubmission) : SchemeGatewayPort {
        var calls = 0
        override suspend fun submit(message: SwiftMessage) = error("not used")
        override suspend fun submitInstitutionTransfer(transfer: InstitutionTransfer): InstitutionTransferSubmission {
            calls++
            return next()
        }
    }

    private val mapper = ObjectMapper()
    private val repo = FakeRepo()
    private val gateway =
        Gateway { InstitutionTransferSubmission(InstitutionTransferVerdict.SETTLED, null, "<pacs009/>") }
    private val service = InstitutionTransferService(
        repo,
        gateway,
        mapper,
        Clock.fixed(InstitutionTransferFixtures.NOW, ZoneOffset.UTC),
        1,
    )

    private fun statuses() = repo.outbox.map { mapper.readTree(it.payload).path("status").asText() }

    @Test
    fun `ACSC emits ACCEPTED, SENT and SETTLED in order and ends SETTLED`(): Unit = runBlocking {
        val m = release(extra = mapOf("ssiVersion" to 3))
        val t = service.onReleased(parsed(m))
        assertThat(t.status).isEqualTo(InstitutionTransferStatus.SETTLED)
        assertThat(t.rawPacs009).isEqualTo("<pacs009/>")
        assertThat(t.rawMt202).contains(":32A:")
        assertThat(statuses()).containsExactly("ACCEPTED", "SENT", "SETTLED")
        assertThat(repo.outbox.map { it.createdAt }).isSorted.doesNotHaveDuplicates()
        val settled = mapper.readTree(repo.outbox.last().payload)
        assertThat(settled.path("paymentId").asText()).isEqualTo(m["paymentId"])
        assertThat(settled.path("idempotencyKey").asText()).isEqualTo(m["idempotencyKey"])
        assertThat(settled.path("messageType").asText()).isEqualTo("pacs.009")
        assertThat(settled.path("schemeReference").asText()).isEqualTo(t.uetr)
        assertThat(settled.path("ssiVersion").asLong()).isEqualTo(3L)
        assertThat(settled.path("synthetic").asBoolean()).isTrue()
        assertThat(repo.outbox).allMatch { it.synthetic && it.aggregateId == t.paymentId }
    }

    @Test
    fun `ACSP leaves the transfer SENT`(): Unit = runBlocking {
        gateway.next = { InstitutionTransferSubmission(InstitutionTransferVerdict.IN_PROCESS, null, "<x/>") }
        assertThat(service.onReleased(parsed(release())).status).isEqualTo(InstitutionTransferStatus.SENT)
        assertThat(statuses()).containsExactly("ACCEPTED", "SENT")
    }

    @Test
    fun `RJCT is REJECTED with the scheme reason code`(): Unit = runBlocking {
        gateway.next = { InstitutionTransferSubmission(InstitutionTransferVerdict.REJECTED, "AC04", "<x/>") }
        val t = service.onReleased(parsed(release()))
        assertThat(t.status).isEqualTo(InstitutionTransferStatus.REJECTED)
        assertThat(statuses()).containsExactly("ACCEPTED", "REJECTED")
        assertThat(mapper.readTree(repo.outbox.last().payload).path("reasonCode").asText()).isEqualTo("AC04")
    }

    @Test
    fun `a non-conforming built message is REJECTED FF01, not retried`(): Unit = runBlocking {
        gateway.next = { throw NonConformingMessageException("bad") }
        assertThat(service.onReleased(parsed(release())).reasonCode).isEqualTo("FF01")
        assertThat(statuses()).containsExactly("ACCEPTED", "REJECTED")
    }

    @Test
    fun `the same release twice is one message and one set of events`(): Unit = runBlocking {
        val m = release()
        val first = service.onReleased(parsed(m))
        val second = service.onReleased(parsed(m))
        assertThat(second.id).isEqualTo(first.id)
        assertThat(repo.rows).hasSize(1)
        assertThat(gateway.calls).isEqualTo(1)
        assertThat(statuses()).containsExactly("ACCEPTED", "SENT", "SETTLED")
    }

    @Test
    fun `a replay after the scheme was unreachable resumes without repeating ACCEPTED`(): Unit = runBlocking {
        val m = release()
        gateway.next = { throw SchemeGatewayUnavailableException(RuntimeException("down")) }
        assertThatThrownBy {
            runBlocking { service.onReleased(parsed(m)) }
        }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
        assertThat(statuses()).containsExactly("ACCEPTED")
        gateway.next = { InstitutionTransferSubmission(InstitutionTransferVerdict.SETTLED, null, "<x/>") }
        assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.SETTLED)
        assertThat(statuses()).containsExactly("ACCEPTED", "SENT", "SETTLED")
    }

    @Test
    fun `the same key under a different payment id is refused, never a second message`(): Unit = runBlocking {
        val m = release()
        service.onReleased(parsed(m))
        val clash = m + ("paymentId" to UUID.randomUUID().toString())
        assertThatThrownBy {
            runBlocking { service.onReleased(parsed(clash)) }
        }.isInstanceOf(InstitutionTransferRefusedException::class.java)
        assertThat(repo.rows).hasSize(1)
    }

    @Test
    fun `an invalid release is refused and emits REJECTED once per payment id`(): Unit = runBlocking {
        val bad = release(amount = "0.00", extra = mapOf("ssiVersion" to 2))
        repeat(2) {
            assertThatThrownBy { runBlocking { service.onReleased(parsed(bad)) } }
                .isInstanceOf(InstitutionTransferRefusedException::class.java).hasMessageStartingWith("AM12")
        }
        assertThat(repo.rows).isEmpty()
        assertThat(statuses()).containsExactly("REJECTED")
        val ev = mapper.readTree(repo.outbox.single().payload)
        assertThat(ev.path("reasonCode").asText()).isEqualTo("AM12")
        assertThat(ev.path("paymentId").asText()).isEqualTo(bad["paymentId"])
        assertThat(ev.path("ssiVersion").asLong()).isEqualTo(2L)
        assertThat(gateway.calls).isZero()
    }

    @Test
    fun `an invalid release without a readable payment id is refused and emits nothing`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.onReleased(parsed(release(extra = mapOf("paymentId" to "x")))) } }
            .isInstanceOf(InstitutionTransferRefusedException::class.java)
        assertThat(repo.outbox).isEmpty()
    }
}
