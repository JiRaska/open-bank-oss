// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.swift.application.port.out.InstitutionTransferRepository
import com.openbank.swift.application.port.out.InstitutionTransferSubmission
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeStatus
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.institution.InstitutionTransferStatus
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.institution.InstitutionTransferFixtures
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The resolution sweeper driven by the REAL scheduler (not a direct call, which would supply the
 * Vert.x context a bare scheduled tick does not): the profile turns the sweeper on with a 1s
 * cadence and no minimum age. Two transfers it must move on its own:
 *
 *  - a release the scheme answered `ACSP` (SENT) and settles later → the sweeper's status query
 *    sees ACSC and the transfer reaches SETTLED, announcing SETTLED once (SENT is not repeated);
 *  - an unknown submission whose status answer is an untyped 404 → MANUAL_RECONCILIATION, never
 *    resent.
 *
 * Profile literals only (a QuarkusTestProfile loads in another classloader — CLAUDE.md).
 */
@QuarkusTest
@TestProfile(InstitutionTransferSweeperIT.SweeperProfile::class)
@QuarkusTestResource(InstitutionTransferSweeperIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.swift.it.PostgresRedisTestResource::class)
class InstitutionTransferSweeperIT {

    class SweeperProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "openbank.swift.institution-transfer.resolution.enabled" to "true",
            "openbank.swift.institution-transfer.resolution.every" to "1s",
            "openbank.swift.institution-transfer.resolution.initial-delay" to "1s",
            "openbank.swift.institution-transfer.resolution.min-age" to "PT0S",
        )

        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(DeferringScheme::class.java)
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    /** Answers ACSP on submission and ACSC on any later status query; `.09` amounts get an untyped 404. */
    @Alternative
    @ApplicationScoped
    class DeferringScheme : SchemeGatewayPort {
        override suspend fun submit(message: SwiftMessage) = error("MT103 path not under test")

        override suspend fun submitInstitutionTransfer(transfer: InstitutionTransfer): InstitutionTransferSubmission {
            SUBMISSIONS.incrementAndGet()
            return InstitutionTransferSubmission(InstitutionTransferVerdict.IN_PROCESS, null, "<pacs009/>")
        }

        override suspend fun queryInstitutionTransferStatus(transfer: InstitutionTransfer): SchemeStatus =
            if (transfer.amount.amount.toPlainString().endsWith(".09")) {
                SchemeStatus.Ambiguous(SchemeStatus.UNTYPED_NOT_FOUND)
            } else {
                SchemeStatus.Known(InstitutionTransferSubmission(InstitutionTransferVerdict.SETTLED, null, ""))
            }

        companion object {
            val SUBMISSIONS = java.util.concurrent.atomic.AtomicInteger()
        }
    }

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var vertx: Vertx

    @Inject
    lateinit var repo: InstitutionTransferRepository

    private val mapper = ObjectMapper()

    private fun release(amount: String) = InstitutionTransferFixtures.release(
        amount = amount,
        extra = mapOf("valueDate" to LocalDate.now(ZoneOffset.UTC).toString()),
    )

    @Test
    fun `the real scheduler settles an ACSP transfer and stops an ambiguous one for a human`() {
        val deferred = release("777.03")
        val ack = CompletableFuture<Boolean>()
        val source = connector.source<kotlin.Any>("treasury-payments-in")
        source.runOnVertxContext(true)
        source.send(
            Message.of(InstitutionTransferFixtures.json(deferred))
                .withAck {
                    ack.complete(true)
                    CompletableFuture.completedFuture(null)
                }
                .withNack {
                    ack.complete(false)
                    CompletableFuture.completedFuture(null)
                },
        )
        assertThat(ack.get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()
        val deferredId = UUID.fromString(deferred["paymentId"] as String)

        // An unknown submission nobody will redeliver: written straight to the store.
        val stuck = InstitutionTransferFixtures.transfer(release("888.09")).let {
            it.copy(
                status = InstitutionTransferStatus.SUBMISSION_UNKNOWN,
                messageId = InstitutionTransfer.messageIdFor(it),
            )
        }
        onVertx { repo.insertClaimed(stuck, emptyList()) }

        assertThat(awaitStatus(deferredId, "SETTLED")).isEqualTo("SETTLED")
        assertThat(awaitStatus(stuck.paymentId, "MANUAL_RECONCILIATION")).isEqualTo("MANUAL_RECONCILIATION")
        assertThat(events(deferredId)).containsExactly("ACCEPTED", "SENT", "SETTLED")
        assertThat(events(stuck.paymentId)).describedAs("internal states are never announced").isEmpty()
        assertThat(DeferringScheme.SUBMISSIONS.get()).describedAs("the stuck one is never resent").isEqualTo(1)
    }

    private fun onVertx(block: suspend () -> kotlin.Any?) {
        val future = CompletableFuture<kotlin.Any?>()
        val duplicated = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(duplicated, true)
        val dispatcher = Executor { command -> duplicated.runOnContext { command.run() } }.asCoroutineDispatcher()
        CoroutineScope(dispatcher).launch {
            runCatching { block() }.fold({ future.complete(it) }, { future.completeExceptionally(it) })
        }
        future.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitStatus(paymentId: UUID, expected: String): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        var last: String? = null
        while (System.nanoTime() < deadline) {
            last = status(paymentId)
            if (last == expected) return last
            Thread.sleep(POLL_MILLIS)
        }
        return last
    }

    private fun status(paymentId: UUID): String? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT status FROM swift_institution_transfers WHERE payment_id = ?").use { st ->
            st.setObject(1, paymentId)
            st.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
    }

    private fun events(paymentId: UUID): List<String> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT payload FROM swift_outbox WHERE aggregate_id = ? ORDER BY created_at, id",
        ).use { st ->
            st.setObject(1, paymentId)
            st.executeQuery().use { r ->
                generateSequence {
                    if (r.next()) mapper.readTree(r.getString(1)).path("status").asText() else null
                }.toList()
            }
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 200L
    }
}
