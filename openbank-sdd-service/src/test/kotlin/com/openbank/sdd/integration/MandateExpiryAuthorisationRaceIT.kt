// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sdd.Fixtures
import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.application.usecase.CollectionScreeningGate
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.infrastructure.scheduler.MandateExpiryWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import io.vertx.core.Vertx
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Drives the real HTTP authorisation transaction until its initial ACTIVE mandate is cached, then
 * suspends only screening. The real expiry writer commits on a separate Vert.x context while HTTP
 * is still pending. A JDBC connection must observe that commit BEFORE CLEAR is released; invoking
 * expiry inside the screening callback would instead risk joining the HTTP transaction.
 *
 * CLEAR resumes on the ORIGINAL HTTP Vert.x context, preserving its already-cached entity. Thus
 * stale-save code, or a locked find without refresh, cannot pass merely by getting a fresh session.
 * No clock sleeps or external screening service are involved. Repository, writer and outbox remain
 * real. Only the remote screening boundary is replaced by a controllable completion.
 */
@QuarkusTest
@TestProfile(MandateExpiryAuthorisationRaceIT.NoDispatchProfile::class)
@QuarkusTestResource(SddOutboxAtomicityIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class MandateExpiryAuthorisationRaceIT {

    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
        )
    }

    @Inject
    lateinit var mandates: SddMandateRepository

    @Inject
    lateinit var writer: MandateExpiryWriter

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    @TestHTTPResource("/api/v1/sdd/collections/authorise")
    lateinit var authoriseUri: URI

    @Test
    @TestSecurity(user = "expiry-race-test", roles = ["ROLE_OPERATOR"])
    fun `expiry committed during screening cannot be resurrected by the original authorisation snapshot`() {
        val mandate = Fixtures.mandate(
            signatureDate = LocalDate.of(2020, 1, 1),
            lastCollectionDate = LocalDate.of(2020, 2, 1),
        ).copy(id = UUID.randomUUID(), umr = "RACE-${UUID.randomUUID().toString().take(20)}")
        VertxContextSupport.subscribeAndAwait { mandates.save(mandate) }
        val entered = CompletableFuture<SddMandate>()
        val releaseClear = CompletableFuture<AuthorisationResult?>()
        val gate = mockk<CollectionScreeningGate>()
        every { gate.screen(any(), any()) } answers {
            val context = requireNotNull(Vertx.currentContext()) { "screening must run on the HTTP Vert.x context" }
            entered.complete(firstArg<SddMandate>())
            val clear: Uni<AuthorisationResult?> = Uni.createFrom().completionStage(releaseClear)
            clear.emitOn(Executor { task -> context.runOnContext { task.run() } })
        }
        QuarkusMock.installMockForType(gate, CollectionScreeningGate::class.java)
        val response = authorise(mandate)
        try {
            val screened = entered.get(10, TimeUnit.SECONDS)
            assertThat(screened).isEqualTo(mandate)
            assertThat(response.isDone).describedAs("HTTP remains suspended at screening").isFalse()

            val expired = VertxContextSupport.subscribeAndAwait {
                writer.expireIfIdle(mandate, LocalDate.of(2026, 11, 19))
                    .ifNoItem().after(Duration.ofSeconds(10)).fail()
            }
            assertThat(expired).describedAs("the independent real expiry transition succeeds").isTrue()
            val committed = row(mandate.id)
            assertThat(committed.status).isEqualTo("EXPIRED")
            assertThat(committed.lastCollection).isEqualTo(mandate.lastCollectionDate)
            assertThat(events(mandate.id)).containsExactly(EXPIRED_EVENT)
            assertThat(response.isDone)
                .describedAs("expiry committed before screening releases the original HTTP transaction").isFalse()

            releaseClear.complete(null)
            val result = response.get(10, TimeUnit.SECONDS)
            assertThat(result.statusCode()).describedAs(result.body()).isEqualTo(200)
            val decision = objectMapper.readTree(result.body())
            assertThat(decision.path("decision").asText()).isEqualTo("REJECT")
            assertThat(decision.path("reasonCode").asText()).isEqualTo("MD01")
            assertThat(decision.path("reason").asText()).contains("EXPIRED")
            assertThat(row(mandate.id))
                .describedAs("rejected authorisation leaves the expired row untouched")
                .isEqualTo(committed)
            assertThat(events(mandate.id))
                .describedAs("no collection-authorised event after expiry")
                .containsExactly(EXPIRED_EVENT)
            verify(exactly = 1) { gate.screen(any(), any()) }
        } finally {
            // Release the request even after a failed assertion; do not leave a transaction suspended
            // into another test. The HTTP timeout also bounds cleanup if the application is broken.
            releaseClear.complete(null)
            runCatching { response.get(30, TimeUnit.SECONDS) }
        }
    }

    private fun authorise(mandate: SddMandate): CompletableFuture<HttpResponse<String>> {
        val body = """
            {"creditorIdentifier":"${mandate.creditorIdentifier}","umr":"${mandate.umr}",
             "scheme":"CORE","sequenceType":"RCUR","amount":42.00,"currency":"EUR",
             "dueDate":"2026-11-19"}
        """.trimIndent()
        val request = HttpRequest.newBuilder(authoriseUri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class Row(val status: String, val lastCollection: LocalDate?, val xmin: String)

    private fun row(id: UUID): Row = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT status, last_collection_date, xmin::text FROM sdd_mandate WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).describedAs("the committed mandate must exist").isTrue()
                Row(rows.getString(1), rows.getDate(2)?.toLocalDate(), rows.getString(3))
            }
        }
    }

    private fun events(id: UUID): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT event_type FROM sdd_outbox WHERE aggregate_id = ? ORDER BY event_type")
            .use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
    }

    private companion object {
        const val EXPIRED_EVENT = "sdd.mandate.expired.v1"
    }
}
