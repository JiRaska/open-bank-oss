// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** A concrete table for the production mapped superclass; no service or global database is touched. */
@Entity
@Table(name = "pit_outbox")
open class PitOutboxEntity : PanacheOutboxEntity()

/**
 * Verifies that PostgreSQL returns the actual mapped superclass state used by [toEntry]. The
 * table is generated from this mapping; service Flyway DDL and the reactive repository are
 * separate integration seams and are not exercised here.
 */
class PanacheOutboxEntityPostgresIT {
    @Test
    fun `persisted outbox row reloads with all delivery state`() {
        val url = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/ob"
        sessionFactory(url).use { factory ->
            val entity = outboxRow()
            val loaded = persistAndReload(factory, entity)
            assertThat(loaded).isEqualTo(entity.toEntry())
            assertThat(loaded.status).isEqualTo(OutboxStatus.SENT)
            assertThat(loaded.synthetic).isTrue()
            assertRawRow(url, entity)
        }
    }

    private fun sessionFactory(url: String): SessionFactory = Configuration()
        .addAnnotatedClass(PitOutboxEntity::class.java)
        .setProperty("hibernate.connection.driver_class", "org.postgresql.Driver")
        .setProperty("hibernate.connection.url", url)
        .setProperty("hibernate.connection.username", "ob")
        .setProperty("hibernate.connection.password", "ob")
        .setProperty("hibernate.hbm2ddl.auto", "create")
        .buildSessionFactory()

    private fun outboxRow(): PitOutboxEntity = PitOutboxEntity().apply {
        eventId = UUID.randomUUID()
        aggregateId = UUID.randomUUID()
        eventType = "PaymentSettled"
        payload = """{"paymentId":"p-1"}"""
        status = OutboxStatus.SENT.name
        attemptCount = 3
        createdAt = Instant.parse("2026-09-29T08:00:00Z")
        updatedAt = Instant.parse("2026-09-29T08:01:00Z")
        sentAt = Instant.parse("2026-09-29T08:02:00Z")
        lastError = "previous attempt failed"
        synthetic = true
    }

    private fun persistAndReload(factory: SessionFactory, entity: PitOutboxEntity): OutboxEntry {
        val id = factory.openSession().use { session ->
            val tx = session.beginTransaction()
            session.persist(entity)
            tx.commit()
            entity.id
        }
        assertThat(id).isNotNull()
        return factory.openSession().use { session ->
            session.find(PitOutboxEntity::class.java, id).toEntry()
        }
    }

    private fun assertRawRow(url: String, entity: PitOutboxEntity) {
        DriverManager.getConnection(url, "ob", "ob").use { connection ->
            connection.prepareStatement(
                "SELECT payload, status, synthetic FROM pit_outbox WHERE event_id = ?",
            ).use { query ->
                query.setObject(1, entity.eventId)
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("payload")).isEqualTo(entity.payload)
                    assertThat(rows.getString("status")).isEqualTo(OutboxStatus.SENT.name)
                    assertThat(rows.getBoolean("synthetic")).isTrue()
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private companion object {
        // PIT reruns this test for each mapped-property mutant. Share one private container per
        // test JVM so the real database proof remains practical in the advisory lane.
        val postgres: GenericContainer<*> by lazy {
            check(DockerClientFactory.instance().isDockerAvailable) {
                "Docker is required for the outbox mapping proof"
            }
            GenericContainer(DockerImageName.parse("postgres:18.6-alpine"))
                .withEnv("POSTGRES_USER", "ob")
                .withEnv("POSTGRES_PASSWORD", "ob")
                .withEnv("POSTGRES_DB", "ob")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort())
                .also { container ->
                    container.start()
                    Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
                }
        }
    }
}
