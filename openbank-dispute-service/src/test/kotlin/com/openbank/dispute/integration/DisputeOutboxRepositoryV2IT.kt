// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.dispute.integration

import com.openbank.dispute.infrastructure.persistence.repository.DisputeOutboxRepositoryImpl
import com.openbank.dispute.it.PostgresTestResource
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepositoryV2
import com.openbank.libs.testing.outbox.OutboxRepositoryV2ConformanceIT
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import java.util.UUID

/**
 * ADR-0327 repository-semantics conformance (per-aggregate order under two concurrent
 * dispatchers, failed-head parking, backoff, stale reclaim, purge safety) against
 * [DisputeOutboxRepositoryImpl] on the real Postgres outbox table and its outbox-v2 migration.
 */
@QuarkusTest
@TestProfile(DisputeOutboxRepositoryV2IT.NoDispatchProfile::class)
@QuarkusTestResource(value = DisputeOutboxDispatchConformanceIT.InMemoryKafkaResource::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(PostgresTestResource::class)
class DisputeOutboxRepositoryV2IT : OutboxRepositoryV2ConformanceIT() {

    // The kit drives OutboxDispatch itself; the scheduled dispatcher would race it for seeded rows.
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
    }

    @Inject
    lateinit var repo: DisputeOutboxRepositoryImpl

    override val repository: OutboxRepositoryV2 get() = repo

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repo.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repo.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
