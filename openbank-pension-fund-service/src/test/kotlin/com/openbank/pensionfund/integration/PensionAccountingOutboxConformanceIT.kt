// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.integration

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepositoryV2
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.libs.testing.outbox.OutboxRepositoryV2ConformanceIT
import com.openbank.pensionfund.infrastructure.accounting.PensionAccountingOutbox
import com.openbank.pensionfund.infrastructure.accounting.PensionAccountingOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import java.util.UUID

/** Exercise the production accounting table's claim, ordering, retry and purge semantics. */
@QuarkusTest
@QuarkusTestResource(
    PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "pension_accounting_outbox_conformance_it")],
)
@TestProfile(PensionAccountingOutboxConformanceIT.NoDelivery::class)
class PensionAccountingOutboxConformanceIT : OutboxRepositoryV2ConformanceIT() {
    class NoDelivery : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "false",
            "openbank.pension-fund.accounting.delivery-enabled" to "false",
        )
    }

    @Inject
    lateinit var outbox: PensionAccountingOutbox

    override val repository: OutboxRepositoryV2 get() = outbox

    override suspend fun seed(message: OutboxMessage) {
        val entity = PensionAccountingOutbox.pending().also {
            it.eventId = message.eventId
            it.aggregateId = message.aggregateId
            it.eventType = message.eventType
            it.payload = message.payload
            it.createdAt = message.createdAt
            it.updatedAt = message.createdAt
            it.synthetic = message.synthetic
        }
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO funds (id, name, isin, lei, depositary_reference, custody_account_reference, " +
                        "currency, risk_class, mandatory_conservative, management_fee_rate, launch_nav_per_unit, " +
                        "status, created_at, updated_at, product_revision_id) VALUES " +
                        "(:id, 'Outbox conformance fund', :isin, '315700ABCDEF12345678', 'TEST-DEPOSITARY', " +
                        "'TEST-CUSTODY', 'CZK', 3, false, 0.01, 1, 'ACTIVE', :at, :at, NULL) " +
                        "ON CONFLICT (id) DO NOTHING",
                ).setParameter("id", message.aggregateId)
                    .setParameter("isin", message.aggregateId.toString().replace("-", "").take(12))
                    .setParameter("at", message.createdAt).executeUpdate().flatMap { session.persist(entity) }
            }
        }.awaitSuspending()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(
                "SELECT * FROM pension_fund_accounting_outbox WHERE event_id = :id",
                PensionAccountingOutboxEntity::class.java,
            ).setParameter("id", eventId).resultList.map { it.firstOrNull()?.toEntry() }
        }
    }.awaitSuspending()
}
