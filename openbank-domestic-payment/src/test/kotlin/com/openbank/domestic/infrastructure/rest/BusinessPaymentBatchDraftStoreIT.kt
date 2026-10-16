// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.integration.DomesticPaymentBootSmokeIT
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
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
        Panache.withSession { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
    }

    private val summary = BusinessPaymentBatchDraftResource.Summary(1, 125)
    private fun request() = BusinessPaymentBatchDraftResource.Create(
        UUID.randomUUID(),
        listOf(
            BusinessPaymentBatchDraftResource.Item(UUID.randomUUID(), "123456789", "0800", "Supplier", 125, "CZK"),
        ),
    )
    private fun itemsJson() =
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(request().items)

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
    fun `replacement records the editing human and rejects stale revision`() {
        val company = UUID.randomUUID()
        val creator = UUID.randomUUID()
        val editor = UUID.randomUUID()
        val row = db {
            store.create(company, creator, UUID.randomUUID().toString(), "d".repeat(64), request(), summary)
        }.first
        val original = row.originalResponseJson
        val edited = db { store.replace(company, row.id, editor, row.revision, itemsJson(), summary.copy(total = 250)) }
        assertEquals(editor, edited?.updatedByPartyId)
        val replay = db { store.replay(company, creator, row.idempotencyKey, row.requestHash) }
        assertEquals(original, replay?.originalResponseJson)
        val originalTotal = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(replay?.originalResponseJson).path("totalAmountMinor").asInt()
        assertEquals(125, originalTotal)
        assertEquals(250, edited?.amountMinor)
        assertThrows(BatchDraftConflict::class.java) {
            db { store.replace(company, row.id, creator, row.revision, itemsJson(), summary) }
        }
    }

    @Test
    fun `only one concurrent replacement can consume a revision`() {
        val company = UUID.randomUUID()
        val row = db {
            store.create(company, UUID.randomUUID(), UUID.randomUUID().toString(), "c".repeat(64), request(), summary)
        }.first
        val calls = (1..2).map {
            CompletableFuture.supplyAsync {
                runCatching {
                    db {
                        store.replace(
                            company,
                            row.id,
                            UUID.randomUUID(),
                            row.revision,
                            itemsJson(),
                            summary,
                        )
                    }
                }
            }
        }
        val outcomes = calls.map { it.join() }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is BatchDraftConflict })
        assertNotNull(db { store.get(company, row.id) }?.updatedByPartyId)
    }

    @Test
    fun `concurrent create with the same key returns one immutable result`() {
        val company = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        val payload = request()
        val calls = (1..2).map {
            CompletableFuture.supplyAsync {
                VertxContextSupport.subscribeAndAwait {
                    uni(CoroutineScope(Dispatchers.Unconfined)) {
                        store.create(company, actor, key, "e".repeat(64), payload, summary)
                    }
                }
            }
        }
        val results = calls.map { it.join() }
        assertEquals(1, results.count { !it.second })
        assertEquals(1, results.count { it.second })
        assertEquals(results[0].first.id, results[1].first.id)
        assertEquals(results[0].first.originalResponseJson, results[1].first.originalResponseJson)
    }
}
