// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.institution

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.application.port.out.InstitutionTransferMetrics
import com.openbank.swift.application.port.out.InstitutionTransferRejection
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.InstitutionTransferSubmission
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.NonConformingMessageException
import com.openbank.swift.application.port.out.ReconciliationCause
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SchemeStatus
import com.openbank.swift.application.port.out.SwiftOutboxMessage
import com.openbank.swift.application.usecase.InstitutionTransferRefusedException
import com.openbank.swift.application.usecase.InstitutionTransferService
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.InstitutionTransferRail
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
    /** In-memory repository with the real claim rule: one owner per payment id / key, either outcome. */
    private class FakeRepo : InstitutionTransferRepository {
        val rows = linkedMapOf<UUID, InstitutionTransfer>()
        val outbox = mutableListOf<SwiftOutboxMessage>()
        val rejections = linkedMapOf<UUID, InstitutionTransferRejection>()
        val claims = linkedMapOf<UUID, Pair<String?, String>>()
        var stealNextResend = false

        private fun claim(paymentId: UUID, key: String?, outcome: String): Boolean {
            if (claims.containsKey(paymentId) || (key != null && claims.values.any { it.first == key })) return false
            claims[paymentId] = key to outcome
            return true
        }

        override suspend fun findByIdempotencyKey(key: String) = rows.values.firstOrNull { it.idempotencyKey == key }
        override suspend fun findByPaymentId(paymentId: UUID) = rows.values.firstOrNull { it.paymentId == paymentId }
        override suspend fun saveWithOutbox(
            transfer: InstitutionTransfer,
            outbox: List<SwiftOutboxMessage>,
        ): InstitutionTransfer {
            val saved = transfer.copy(version = (rows[transfer.id]?.version ?: -1) + 1)
            rows[transfer.id] = saved
            this.outbox += outbox
            return saved
        }
        override suspend fun insertClaimed(transfer: InstitutionTransfer, outbox: List<SwiftOutboxMessage>): Boolean {
            if (!claim(transfer.paymentId, transfer.idempotencyKey, "TRANSFER")) return false
            saveWithOutbox(transfer, outbox)
            return true
        }
        override suspend fun recordRejectionOnce(
            rejection: InstitutionTransferRejection,
            outbox: SwiftOutboxMessage,
        ): Boolean {
            if (!claim(rejection.paymentId, rejection.idempotencyKey, "REJECTION")) return false
            rejections[rejection.paymentId] = rejection
            this.outbox += outbox
            return true
        }
        override suspend fun takeResendMarker(transfer: InstitutionTransfer): Boolean {
            val current = rows[transfer.id] ?: return false
            if (stealNextResend) {
                rows[transfer.id] = current.copy(resendTaken = true, version = current.version + 1)
                stealNextResend = false
            }
            val now = rows.getValue(transfer.id)
            if (now.version != transfer.version || now.resendTaken) return false
            rows[transfer.id] = now.copy(resendTaken = true, version = now.version + 1)
            return true
        }
    }

    /**
     * A scheme with the clearing simulator's real property: it remembers what it took, by UETR.
     * [timeoutAfterAccept] makes it take the transfer and then lose the answer — the case an
     * automatic retry would turn into a second payment. [queryAnswer] overrides the status reply.
     */
    private class Gateway(var next: () -> InstitutionTransferSubmission) : SchemeGatewayPort {
        var calls = 0
        var queries = 0
        var timeoutAfterAccept = false
        var queryUnavailable = false
        var queryAnswer: SchemeStatus? = null
        val taken = linkedMapOf<String, InstitutionTransferSubmission>()
        val identities = mutableListOf<Pair<String?, java.time.Instant?>>()
        override suspend fun submit(message: SwiftMessage) = error("not used")
        override suspend fun submitInstitutionTransfer(transfer: InstitutionTransfer): InstitutionTransferSubmission {
            calls++
            identities += transfer.messageId to transfer.messageCreatedAt
            val answer = taken.getOrPut(transfer.uetr) { next() }
            if (timeoutAfterAccept) throw SchemeGatewayUnavailableException(RuntimeException("read timeout"))
            return answer
        }
        override suspend fun queryInstitutionTransferStatus(transfer: InstitutionTransfer): SchemeStatus {
            queries++
            if (queryUnavailable) throw SchemeGatewayUnavailableException(RuntimeException("down"))
            queryAnswer?.let { return it }
            return taken[transfer.uetr]?.let { SchemeStatus.Known(it) } ?: SchemeStatus.NotKnown
        }
    }

    private class Metrics : InstitutionTransferMetrics {
        val stops = mutableListOf<ReconciliationCause>()
        override fun manualReconciliation(rail: InstitutionTransferRail, cause: ReconciliationCause) {
            stops += cause
        }
    }

    private val mapper = ObjectMapper()
    private val repo = FakeRepo()
    private val metrics = Metrics()
    private val gateway =
        Gateway { InstitutionTransferSubmission(InstitutionTransferVerdict.SETTLED, null, "<pacs009/>") }
    private fun service(clock: Clock = Clock.fixed(InstitutionTransferFixtures.NOW, ZoneOffset.UTC)) =
        InstitutionTransferService(repo, gateway, metrics, mapper, clock, 1)
    private val service = service()

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
    fun `a timeout after the scheme took it is resolved by status query - exactly one submission`(): Unit =
        runBlocking {
            val m = release()
            gateway.timeoutAfterAccept = true
            assertThatThrownBy {
                runBlocking { service.onReleased(parsed(m)) }
            }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
            assertThat(repo.rows.values.single().status).isEqualTo(InstitutionTransferStatus.SUBMISSION_UNKNOWN)
            assertThat(statuses()).containsExactly("ACCEPTED")
            gateway.timeoutAfterAccept = false
            assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.SETTLED)
            assertThat(gateway.calls).describedAs("never resubmitted").isEqualTo(1)
            assertThat(gateway.queries).isEqualTo(1)
            assertThat(gateway.taken).hasSize(1)
            assertThat(statuses()).containsExactly("ACCEPTED", "SENT", "SETTLED")
        }

    @Test
    fun `an unknown submission the scheme never received is resent with the same MsgId and CreDtTm`(): Unit =
        runBlocking {
            val m = release()
            var first = true
            gateway.next = {
                if (first) {
                    first = false
                    throw SchemeGatewayUnavailableException(RuntimeException("connect refused"))
                }
                InstitutionTransferSubmission(InstitutionTransferVerdict.SETTLED, null, "<x/>")
            }
            assertThatThrownBy {
                runBlocking { service.onReleased(parsed(m)) }
            }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
            assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.SETTLED)
            assertThat(gateway.queries).isEqualTo(1)
            assertThat(gateway.identities).hasSize(2)
            assertThat(gateway.identities.distinct()).describedAs("same pacs.009 identity on resend").hasSize(1)
            assertThat(gateway.identities.first().first).startsWith("FI-")
        }

    @Test
    fun `a status query that cannot be answered keeps the transfer unknown and sends nothing`(): Unit = runBlocking {
        val m = release()
        gateway.timeoutAfterAccept = true
        assertThatThrownBy {
            runBlocking { service.onReleased(parsed(m)) }
        }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
        gateway.queryUnavailable = true
        assertThatThrownBy {
            runBlocking { service.onReleased(parsed(m)) }
        }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
        assertThat(gateway.calls).isEqualTo(1)
        assertThat(repo.rows.values.single().status).isEqualTo(InstitutionTransferStatus.SUBMISSION_UNKNOWN)
    }

    @Test
    fun `a replay of a settled payment after its value date passed emits no REJECTED and no second transfer`(): Unit =
        runBlocking {
            val m = release() + ("valueDate" to InstitutionTransferFixtures.TODAY.toString())
            service.onReleased(parsed(m))
            val later = InstitutionTransferService(
                repo,
                gateway,
                metrics,
                mapper,
                Clock.fixed(InstitutionTransferFixtures.NOW.plus(java.time.Duration.ofDays(10)), ZoneOffset.UTC),
                1,
            )
            assertThat(later.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.SETTLED)
            assertThat(repo.rows).hasSize(1)
            assertThat(repo.rejections).isEmpty()
            assertThat(statuses()).containsExactly("ACCEPTED", "SENT", "SETTLED")
        }

    @Test
    fun `a valid release after a recorded rejection is dead-lettered without a transfer`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        assertThatThrownBy { runBlocking { service.onReleased(parsed(release(paymentId = id, amount = "0.00"))) } }
            .isInstanceOf(InstitutionTransferRefusedException::class.java)
        assertThatThrownBy { runBlocking { service.onReleased(parsed(release(paymentId = id))) } }
            .isInstanceOf(InstitutionTransferRefusedException::class.java).hasMessageContaining("already claimed")
        assertThat(repo.rows).isEmpty()
        assertThat(gateway.calls).isZero()
        assertThat(statuses()).containsExactly("REJECTED")
    }

    @Test
    fun `a rejection is never recorded for a payment the rail already holds`(): Unit = runBlocking {
        val m = release()
        service.onReleased(parsed(m))
        val written = repo.recordRejectionOnce(
            InstitutionTransferRejection(
                UUID.fromString(m["paymentId"] as String),
                null,
                "FF01",
                "x",
                false,
                InstitutionTransferFixtures.NOW,
            ),
            repo.outbox.first(),
        )
        assertThat(written).isFalse()
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

    private suspend fun leaveUnknown(m: Map<String, Any?>) {
        gateway.timeoutAfterAccept = true
        assertThatThrownBy {
            runBlocking { service.onReleased(parsed(m)) }
        }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
        gateway.timeoutAfterAccept = false
        assertThat(repo.rows.values.single().status).isEqualTo(InstitutionTransferStatus.SUBMISSION_UNKNOWN)
    }

    @Test
    fun `an untyped 404 from the status query stops in MANUAL_RECONCILIATION and is never resent`(): Unit =
        runBlocking {
            val m = release()
            leaveUnknown(m)
            gateway.queryAnswer = SchemeStatus.Ambiguous(SchemeStatus.UNTYPED_NOT_FOUND)
            assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.MANUAL_RECONCILIATION)
            assertThat(gateway.calls).isEqualTo(1)
            assertThat(metrics.stops).containsExactly(ReconciliationCause.UNTYPED_NOT_FOUND)
            assertThat(statuses()).containsExactly("ACCEPTED")
            // Terminal for automation: a further replay does nothing.
            assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.MANUAL_RECONCILIATION)
            assertThat(gateway.queries).isEqualTo(1)
        }

    @Test
    fun `a status report about another message stops in MANUAL_RECONCILIATION`(): Unit = runBlocking {
        val m = release()
        leaveUnknown(m)
        gateway.queryAnswer = SchemeStatus.Ambiguous(SchemeStatus.MISMATCHED)
        assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.MANUAL_RECONCILIATION)
        assertThat(metrics.stops).containsExactly(ReconciliationCause.MISMATCHED_STATUS)
        assertThat(gateway.calls).isEqualTo(1)
    }

    @Test
    fun `a typed not-known licenses exactly one resend, and still unknown after it stops for a human`(): Unit =
        runBlocking {
            val m = release()
            gateway.next = { throw SchemeGatewayUnavailableException(RuntimeException("connect refused")) }
            assertThatThrownBy {
                runBlocking { service.onReleased(parsed(m)) }
            }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
            // The resend also fails to get an answer…
            assertThatThrownBy {
                runBlocking { service.onReleased(parsed(m)) }
            }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
            assertThat(gateway.calls).isEqualTo(2)
            assertThat(repo.rows.values.single().resendTaken).isTrue()
            // …and the scheme still does not know it: no third send.
            assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.MANUAL_RECONCILIATION)
            assertThat(gateway.calls).isEqualTo(2)
            assertThat(metrics.stops).containsExactly(ReconciliationCause.UNKNOWN_AFTER_RESEND)
        }

    @Test
    fun `a resend marker taken by another worker stops in MANUAL_RECONCILIATION without sending`(): Unit = runBlocking {
        val m = release()
        gateway.next = { throw SchemeGatewayUnavailableException(RuntimeException("connect refused")) }
        assertThatThrownBy {
            runBlocking { service.onReleased(parsed(m)) }
        }.isInstanceOf(SchemeGatewayUnavailableException::class.java)
        repo.stealNextResend = true
        assertThat(service.onReleased(parsed(m)).status).isEqualTo(InstitutionTransferStatus.MANUAL_RECONCILIATION)
        assertThat(gateway.calls).isEqualTo(1)
        assertThat(metrics.stops).containsExactly(ReconciliationCause.RESEND_CONTENDED)
    }

    @Test
    fun `a rejection that won the claim keeps a later valid release from becoming a transfer`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val m = release(paymentId = id)
        // The rejection path claims first (e.g. an invalid replay processed concurrently).
        assertThat(
            repo.recordRejectionOnce(
                InstitutionTransferRejection(
                    id,
                    m["idempotencyKey"] as String,
                    "FF01",
                    "x",
                    false,
                    InstitutionTransferFixtures.NOW,
                ),
                SwiftOutboxMessage(aggregateId = id, eventType = "t", payload = "{}"),
            ),
        ).isTrue()
        assertThatThrownBy { runBlocking { service.onReleased(parsed(m)) } }
            .isInstanceOf(InstitutionTransferRefusedException::class.java).hasMessageContaining("already claimed")
        assertThat(repo.rows).isEmpty()
        assertThat(repo.claims).hasSize(1)
        assertThat(gateway.calls).isZero()
    }
}
