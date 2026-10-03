// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.clearing.integration

import com.openbank.clearing.infrastructure.persistence.repository.ClearingOutboxRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepositoryV2
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import com.openbank.libs.testing.outbox.OutboxRepositoryV2ConformanceIT
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.inject.Inject
import java.util.UUID

/**
 * ADR-0327 repository-semantics conformance (per-aggregate order under two concurrent
 * dispatchers, failed-head parking, backoff, stale reclaim, purge safety) against
 * [ClearingOutboxRepositoryImpl] on the real Postgres outbox table and its outbox-v2 migration.
 * The kit drives OutboxDispatch itself, so the scheduled dispatcher stays off
 * ([OutboxRepositoryIsolationProfile]) rather than racing it for seeded rows.
 */
@QuarkusTest
@TestProfile(OutboxRepositoryIsolationProfile::class)
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_it")],
)
class ClearingOutboxRepositoryV2IT : OutboxRepositoryV2ConformanceIT() {

    @Inject
    lateinit var repo: ClearingOutboxRepositoryImpl

    override val repository: OutboxRepositoryV2 get() = repo

    override suspend fun seed(message: OutboxMessage) {
        Panache.withTransaction { repo.persistInTransaction(message) }.awaitSuspending()
    }

    override suspend fun findEntry(eventId: UUID): OutboxEntry? =
        Panache.withSession { repo.find("eventId", eventId).firstResult() }.awaitSuspending()?.toEntry()
}
