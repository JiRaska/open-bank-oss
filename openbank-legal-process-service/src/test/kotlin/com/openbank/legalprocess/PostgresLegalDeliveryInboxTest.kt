// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.legalprocess

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresLegalDeliveryInboxTest {
    private val postgres = PostgreSQLContainer("postgres:18.6-alpine")
    private val dataSource = PGSimpleDataSource()
    private val inbox = PostgresLegalDeliveryInbox(dataSource)

    @BeforeAll
    fun start() {
        postgres.start()
        dataSource.setURL(postgres.jdbcUrl)
        dataSource.user = postgres.username
        dataSource.password = postgres.password
        val migration = checkNotNull(javaClass.getResource("/db/migration/V1__legal_delivery_inbox.sql")).readText()
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(migration) }
        }
    }

    @AfterAll
    fun stop() {
        postgres.stop()
    }

    @Test
    fun `concurrent redelivery records exactly one receipt for CZ SK and DE`() {
        IntakeRoute.entries.forEach { route ->
            val delivery = sample(route)
            val results = race(delivery, delivery)
            val stored = results.filterIsInstance<LegalDeliveryOutcome.Received>().single()
            val duplicate = results.filterIsInstance<LegalDeliveryOutcome.Duplicate>().single()
            assertEquals(stored.receiptId, duplicate.receiptId)
            assertEquals(stored.receiptId, readReceipt(delivery))
        }
    }

    @Test
    fun `concurrent contradictory evidence never overwrites first committed payload`() {
        IntakeRoute.entries.forEach { route ->
            val first = sample(route)
            val second = first.copy(contentSha256 = "b".repeat(64), evidenceId = UUID.randomUUID())
            val results = race(first, second)
            val winner = if (results.first() is LegalDeliveryOutcome.Received) first else second
            val receipt = results.filterIsInstance<LegalDeliveryOutcome.Received>().single()
            assertEquals(1, results.count { it == LegalDeliveryOutcome.Conflict })
            assertEquals(LegalDeliveryOutcome.Duplicate(receipt.receiptId), inbox.record(winner))
            assertEquals(receipt.receiptId, readReceipt(winner))
        }
    }

    @Test
    fun `equal delivery IDs belonging to independent mailboxes remain independent`() {
        val first = sample(IntakeRoute.CZ)
        assertInstanceOf(LegalDeliveryOutcome.Received::class.java, inbox.record(first))
        assertInstanceOf(
            LegalDeliveryOutcome.Received::class.java,
            inbox.record(first.copy(sourceNamespace = "second-mailbox")),
        )
    }

    private fun race(first: ObservedLegalDelivery, second: ObservedLegalDelivery): List<LegalDeliveryOutcome> {
        val barrier = CyclicBarrier(2)
        return Executors.newFixedThreadPool(2).use { executor ->
            listOf(first, second).map { delivery ->
                executor.submit<LegalDeliveryOutcome> {
                    barrier.await(10, TimeUnit.SECONDS)
                    inbox.record(delivery)
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        }
    }

    private fun sample(route: IntakeRoute) = ObservedLegalDelivery(
        "synthetic-mailbox",
        UUID.randomUUID().toString(),
        route,
        UUID.randomUUID(),
        "a".repeat(64),
    )

    private fun readReceipt(delivery: ObservedLegalDelivery): UUID = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT receipt_id, evidence_id, content_sha256, route_country FROM legal_delivery_inbox " +
                "WHERE source_namespace = ? AND delivery_id = ?",
        ).use { statement ->
            statement.setString(1, delivery.sourceNamespace)
            statement.setString(2, delivery.deliveryId)
            statement.executeQuery().use { row ->
                check(row.next())
                assertEquals(delivery.evidenceId, row.getObject("evidence_id", UUID::class.java))
                assertEquals(delivery.contentSha256, row.getString("content_sha256"))
                assertEquals(delivery.route.name, row.getString("route_country"))
                val receipt = row.getObject("receipt_id", UUID::class.java)
                check(!row.next())
                receipt
            }
        }
    }
}
