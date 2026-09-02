// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.integration

import com.openbank.cardissuance.infrastructure.persistence.entity.CardOutboxEntity
import com.openbank.cardissuance.infrastructure.persistence.repository.CardOutboxRepositoryImpl
import com.openbank.cardissuance.it.PostgresRedisTestResource
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The requeue path for outbox rows stranded in terminal DEAD (#4005), driven over **real HTTP**
 * against the **real reactive repository and a real Postgres**.
 *
 * Three things only this shape can prove, each of which a unit test calling
 * `CardOutboxAdminResource.requeueDead(...)` directly reports green on while broken:
 *
 * 1. **The route is actually SERVED.** A Kotlin annotation binds to the next declaration, so a
 *    stray top-level function between `@Path` and the class silently steals it — the class then
 *    carries no path, RESTEasy never registers it, and every request 404s while the resource
 *    remains a perfectly good CDI bean. `requeues every DEAD row` asserts a 200; a 404 here means
 *    the endpoint does not exist no matter what the code reads like.
 * 2. **The bulk update commits.** `CardOutboxEntity` has an application-assigned `@Id`, which
 *    makes Panache reactive `persist()` INSERT-only — a mocked repository cannot distinguish a
 *    write that landed from one that silently did not.
 * 3. **The absent optional query parameter is not a 500.** JAX-RS injects `null` for a missing
 *    `@QueryParam`; only a real request exercises that.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CardOutboxRequeueIT {

    @Inject
    lateinit var repository: CardOutboxRepositoryImpl

    private fun persistDead(eventId: UUID, attempts: Int = 10) {
        VertxContextSupport.subscribeAndAwait {
            Panache.withTransaction {
                repository.persist(
                    CardOutboxEntity().apply {
                        this.eventId = eventId
                        this.aggregateId = UUID.randomUUID()
                        this.eventType = "card.issued.v1"
                        this.payload = """{"eventId":"$eventId"}"""
                        this.status = OutboxStatus.DEAD.name
                        this.attemptCount = attempts
                        this.lastError = "circuit breaker is open"
                        // The live rows carry 1970 (#3272). Kept here so the test data is the
                        // shape actually stranded in the cluster.
                        this.createdAt = Instant.EPOCH
                        this.updatedAt = Instant.EPOCH
                    },
                )
            }
        }
    }

    private fun rowFor(eventId: UUID): CardOutboxEntity? = VertxContextSupport.subscribeAndAwait {
        Panache.withSession { repository.find("eventId", eventId).firstResult() }
    }

    @BeforeEach
    fun clean() {
        VertxContextSupport.subscribeAndAwait { Panache.withTransaction { repository.deleteAll() } }
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `requeues every DEAD row and gives each a fresh attempt budget`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        persistDead(first)
        persistDead(second)

        given()
            .header("X-Operator-Id", "admin@openbank.local")
            .`when`()
            .post("/api/v1/cards/outbox/requeue")
            .then()
            .statusCode(200)
            .body("requeued", equalTo(2))
            .body("deadRemaining", equalTo(0))

        listOf(first, second).forEach { id ->
            val row = rowFor(id)
            assertThat(row).describedAs("row $id").isNotNull
            assertThat(row!!.status).isEqualTo(OutboxStatus.PENDING.name)
            // The load-bearing half: left at 10 the row re-parks as DEAD on the first failure.
            assertThat(row.attemptCount).isZero()
            assertThat(row.lastError).isNull()
            // createdAt is NOT restamped — it is the claim ordering key, not a fact to rewrite.
            assertThat(row.createdAt).isEqualTo(Instant.EPOCH)
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `requeues exactly one row when eventId names it`() {
        val target = UUID.randomUUID()
        val bystander = UUID.randomUUID()
        persistDead(target)
        persistDead(bystander)

        given()
            .header("X-Operator-Id", "admin@openbank.local")
            .queryParam("eventId", target.toString())
            .`when`()
            .post("/api/v1/cards/outbox/requeue")
            .then()
            .statusCode(200)
            .body("requeued", equalTo(1))
            .body("deadRemaining", equalTo(1))

        assertThat(rowFor(target)!!.status).isEqualTo(OutboxStatus.PENDING.name)
        assertThat(rowFor(bystander)!!.status).isEqualTo(OutboxStatus.DEAD.name)
    }

    /**
     * A malformed `eventId` must be a 400 and not a 500. libs-runtime maps
     * `IllegalArgumentException` to 400; there is deliberately no card-issuance exception mapper.
     */
    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `rejects a non-UUID eventId with 400`() {
        given()
            .header("X-Operator-Id", "admin@openbank.local")
            .queryParam("eventId", "not-a-uuid")
            .`when`()
            .post("/api/v1/cards/outbox/requeue")
            .then()
            .statusCode(400)
    }

    /**
     * The header is genuinely required, so its absence must be a 400 — the case the fleet-wide
     * non-nullable-param defect answered with a 500 for exactly the input the guard was written
     * for. Declared nullable + `requireNotNull` in the body is what makes this reachable.
     */
    @Test
    @TestSecurity(user = "admin", roles = ["ROLE_ADMIN"])
    fun `rejects a missing X-Operator-Id with 400`() {
        given()
            .`when`()
            .post("/api/v1/cards/outbox/requeue")
            .then()
            .statusCode(400)
    }

    /** ROLE_OPERATOR is deliberately NOT enough — matches the rego's admin-only grant. */
    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `refuses a caller who only holds ROLE_OPERATOR`() {
        given()
            .header("X-Operator-Id", "operator@openbank.local")
            .`when`()
            .post("/api/v1/cards/outbox/requeue")
            .then()
            .statusCode(403)
    }
}
