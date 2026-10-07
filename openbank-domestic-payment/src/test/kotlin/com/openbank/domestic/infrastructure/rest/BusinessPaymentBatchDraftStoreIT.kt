// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.integration.DomesticPaymentBootSmokeIT
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

@QuarkusTest
@QuarkusTestResource(DomesticPaymentBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class BusinessPaymentBatchDraftStoreIT {
    @Inject lateinit var store: BusinessPaymentBatchDraftStore

    private fun <T> db(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }

    private val summary = BusinessPaymentBatchDraftResource.Summary(0, 0)
    private fun request() = BusinessPaymentBatchDraftResource.Create(UUID.randomUUID())

    @Test
    fun `company ownership and durable idempotency binding`() {
        val company = UUID.randomUUID()
        val other = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        val payload = request()
        val first = db { store.create(company, actor, key, "a".repeat(64), payload, summary) }
        assertEquals(false, first.second)
        assertNull(db { store.get(other, first.first.id) })
        assertNotNull(db { store.get(company, first.first.id) })
        assertEquals(emptyList<Any>(), db { store.list(other, 0, 20) })
        val otherCompany = db { store.create(other, actor, key, "a".repeat(64), payload, summary) }
        assertEquals(false, otherCompany.second)
        assertNull(db { store.get(company, otherCompany.first.id) })
        // A fresh database query, with no in-memory idempotency cache, returns the committed row.
        val replay = db { store.create(company, actor, key, "a".repeat(64), payload, summary) }
        assertEquals(first.first.id, replay.first.id)
        assertEquals(true, replay.second)
        assertThrows(BatchDraftConflict::class.java) {
            db { store.create(company, actor, key, "b".repeat(64), payload, summary) }
        }
        assertThrows(BatchDraftConflict::class.java) {
            db { store.create(company, UUID.randomUUID(), key, "a".repeat(64), payload, summary) }
        }
    }

    @Test
    fun `only one concurrent replacement can consume a revision`() {
        val company = UUID.randomUUID()
        val row = db {
            store.create(company, UUID.randomUUID(), UUID.randomUUID().toString(), "c".repeat(64), request(), summary)
        }.first
        val calls = (1..2).map { index ->
            CompletableFuture.supplyAsync {
                runCatching {
                    db {
                        store.replace(
                            company, row.id, row.revision, if (index == 1) "[]" else "[ ]",
                            summary,
                        )
                    }
                }
            }
        }
        val outcomes = calls.map { it.join() }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is BatchDraftConflict })
    }
}
