// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.party.application.port.`in`.GrantMandateCommand
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * #16015: a kyb case that reached SIGNED (and so granted one mandate per signer on the entity)
 * and then ended ABANDONED or REJECTED must leave none of those mandates ACTIVE.
 *
 * Real Postgres, real Redpanda, the real `kyb-events-in` channel. The SIGNED half is what
 * kyb-service's `grantMandates` does to party-service — one `grantMandate` per signer with
 * `evidenceRef = kyb-case:<caseId>:signature:<ref>` — and the ending half is the event body kyb
 * serialises (`BusinessOnboardingAbandoned` / `BusinessOnboardingRejected`), written as a literal so
 * a renamed field on either side reddens this test instead of agreeing with itself.
 *
 * Must-not-revoke control in every case: a mandate on the same entity from a DIFFERENT kyb case
 * stays ACTIVE. Idempotency: the ending event delivered twice revokes each mandate once.
 */
@QuarkusTest
@QuarkusTestResource(KybEndedCaseMandateRevocationIT.FromEarliest::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
    restrictToAnnotatedClass = true,
)
class KybEndedCaseMandateRevocationIT {

    class FromEarliest : QuarkusTestResourceLifecycleManager {
        // The topic is auto-created on first publish, after the consumer subscribed.
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "mp.messaging.incoming.kyb-events-in.auto.offset.reset" to "earliest",
        )
        override fun stop() = Unit
    }

    @Inject lateinit var repository: PartyRepository

    @Inject lateinit var party: PartyUseCase

    @Inject lateinit var vertx: Vertx

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `a signed case that is then abandoned leaves its mandates REVOKED`() =
        assertEndingRevokes("BUSINESS_ONBOARDING_ABANDONED", "ABANDONED")

    @Test
    fun `a signed case that is then rejected leaves its mandates REVOKED`() =
        assertEndingRevokes("BUSINESS_ONBOARDING_REJECTED", "REJECTED")

    private fun assertEndingRevokes(eventType: String, status: String) {
        val entity = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val otherCase = UUID.randomUUID()
        val signers = listOf(UUID.randomUUID(), UUID.randomUUID())
        val bystander = UUID.randomUUID()
        signed(entity, caseId, signers, bystander, otherCase)
        assertThat(statuses(entity).values).containsOnly("ACTIVE").hasSize(3)

        val body = endingEvent(eventType, status, caseId, entity, signers.first())
        producer().use { p ->
            // Delivered twice: the redelivery must find nothing ACTIVE left and do nothing.
            repeat(2) { p.send(ProducerRecord(KYB_TOPIC, caseId.toString(), body)).get(30, TimeUnit.SECONDS) }
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline && signers.any { statuses(entity)[it] != "REVOKED" }) Thread.sleep(50)

        val after = statuses(entity)
        assertThat(signers.map { after[it] }).describedAs("mandates granted by the ended case").containsOnly("REVOKED")
        assertThat(after[bystander]).describedAs("mandate granted by a different kyb case").isEqualTo("ACTIVE")
        // Wait out any redelivery before counting: one PARTY_MANDATE_REVOKED per signer, not two.
        Thread.sleep(2_000)
        assertThat(revokedEvents(entity)).isEqualTo(signers.size)
    }

    private fun signed(entity: UUID, caseId: UUID, signers: List<UUID>, bystander: UUID, otherCase: UUID) = onVertx {
        repository.save(partyFixture(entity, PartyType.COMPANY))
        (signers + bystander).forEach { repository.save(partyFixture(it, PartyType.INDIVIDUAL)) }
        signers.forEach { signer -> grant(entity, signer, "kyb-case:$caseId:signature:${UUID.randomUUID()}") }
        grant(entity, bystander, "kyb-case:$otherCase:signature:${UUID.randomUUID()}")
    }

    private suspend fun grant(entity: UUID, agent: UUID, evidenceRef: String) {
        party.grantMandate(
            GrantMandateCommand(
                principalPartyId = entity,
                agentPartyId = agent,
                role = MandateRole.LEGAL_REPRESENTATIVE,
                authority = MandateAuthority.SOLE,
                requiredSignatures = 1,
                source = MandateSource.REGISTRY,
                evidenceRef = evidenceRef,
            ),
        )
    }

    /** The JSON kyb-service writes to its outbox for these two event types (KybEventPayloads.kt). */
    private fun endingEvent(eventType: String, status: String, caseId: UUID, entity: UUID, initiator: UUID) = """
        {"eventType":"$eventType","caseId":"$caseId","status":"$status","identifierScheme":"CZ_ICO",
         "identifier":"71000127","country":"CZ","legalName":"Fixture s.r.o.","legalFormClass":"CAPITAL_COMPANY",
         "initiatorPartyId":"$initiator","entityPartyId":"$entity","requiredSignatures":1,"signedCount":2,
         "occurredAt":"${Instant.now()}","actorId":"$initiator","actorType":"CUSTOMER",
         "sourceService":"openbank-kyb-service"}
    """.trimIndent()

    private fun statuses(entity: UUID): Map<UUID, String> = dataSource.connection.use { c ->
        val ps = c.prepareStatement("SELECT agent_party_id, status FROM party_mandates WHERE principal_party_id = ?")
        ps.setObject(1, entity)
        val rs = ps.executeQuery()
        buildMap { while (rs.next()) put(rs.getObject(1, UUID::class.java), rs.getString(2)) }
    }

    private fun revokedEvents(entity: UUID): Int = dataSource.connection.use { c ->
        val ps = c.prepareStatement(
            "SELECT count(*) FROM party_outbox WHERE aggregate_id = ? AND event_type = 'PARTY_MANDATE_REVOKED'",
        )
        ps.setObject(1, entity)
        val rs = ps.executeQuery()
        rs.next()
        rs.getInt(1)
    }

    private fun producer() = KafkaProducer<String, String>(
        Properties().apply {
            put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String::class.java),
            )
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        },
    )

    private fun partyFixture(id: UUID, type: PartyType) = Party(
        id = id,
        partyType = type,
        status = PartyStatus.ACTIVE,
        legalName = "Kyb Mandate Fixture",
        tradingName = null,
        dateOfBirth = null,
        nationality = null,
        taxId = null,
        registrationNumber = null,
        email = "$id@example.invalid",
        phone = null,
        address = null,
        kycStatus = KycStatus.APPROVED,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun onVertx(block: suspend () -> Unit) {
        val done = CompletableFuture<Unit>()
        val context = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(context, true)
        val executor = Executor { command -> context.runOnContext { command.run() } }
        CoroutineScope(executor.asCoroutineDispatcher()).launch {
            runCatching { block() }.onSuccess { done.complete(Unit) }.onFailure { done.completeExceptionally(it) }
        }
        done.get(30, TimeUnit.SECONDS)
    }

    private companion object {
        const val KYB_TOPIC = "openbank.kyb.events"
    }
}
